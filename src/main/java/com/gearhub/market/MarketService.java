package com.gearhub.market;

import io.swagger.v3.oas.annotations.media.Schema;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Leitura da aba Games: lista, ficha de cada jogo e séries para os gráficos. Preços em centavos. */
@Service
public class MarketService {

    @Schema(description = "Jogo na lista da aba Games")
    public record GameSummary(int appid, String name, String headerImage, List<String> genres, boolean free,
            String currency, Integer initialCents, Integer finalCents, int discountPercent, Integer currentPlayers,
            @Schema(description = "Pico de jogadores nas últimas 24 horas") Integer peakPlayers24h,
            @Schema(description = "Porcentagem de avaliações positivas") Integer reviewPercent,
            @Schema(description = "Menor preço já registrado pela nossa coleta") Integer lowestCents) {
    }

    @Schema(description = "Ficha completa de um jogo")
    public record GameDetail(GameSummary summary, String shortDescription, String developers, String releaseDate,
            Integer reviewPositive, Integer reviewNegative, String reviewSummary, OffsetDateTime lowestAt,
            @Schema(description = "Maior número de jogadores já registrado") Integer peakPlayersAll,
            OffsetDateTime trackedSince, OffsetDateTime updatedAt) {
    }

    public record PricePoint(OffsetDateTime takenAt, int initialCents, int finalCents, int discountPercent) {
    }

    public record PlayerPoint(OffsetDateTime takenAt, int players) {
    }

    @Schema(description = "Séries para os gráficos. Preço: um ponto por mudança (linha em degraus)")
    public record History(String range, List<PricePoint> prices, List<PlayerPoint> players) {
    }

    // Intervalo do gráfico e o tamanho do agrupamento dos jogadores
    private static final Map<String, String[]> RANGES = Map.of(
            "24h", new String[] {"24 hours", "hour"},
            "7d", new String[] {"7 days", "hour"},
            "30d", new String[] {"30 days", "day"},
            "all", new String[] {"100 years", "day"});

    private static final Map<String, String> SORTS = Map.of(
            "players", "g.current_players DESC NULLS LAST, g.name",
            "discount", "g.discount_percent DESC, g.current_players DESC NULLS LAST",
            "price", "g.final_cents ASC NULLS FIRST, g.name",
            "name", "g.name");

    private static final String SUMMARY_COLUMNS = """
            g.steam_appid, g.name, g.header_image, array_to_string(g.genres, '|') AS genres, g.is_free, g.currency,
            g.initial_cents, g.final_cents, g.discount_percent, g.current_players,
            (SELECT max(p.players) FROM market_player_snapshots p
             WHERE p.steam_appid = g.steam_appid AND p.taken_at > now() - interval '24 hours') AS peak_24h,
            CASE WHEN coalesce(g.review_positive, 0) + coalesce(g.review_negative, 0) > 0
                 THEN round(100.0 * g.review_positive / (g.review_positive + g.review_negative))::integer END AS review_percent,
            (SELECT min(s.final_cents) FROM market_price_snapshots s WHERE s.steam_appid = g.steam_appid) AS lowest_cents
            """;

    private final JdbcClient jdbc;

    public MarketService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<GameSummary> list(String sort, String search) {
        String order = SORTS.getOrDefault(sort == null ? "players" : sort, SORTS.get("players"));
        String term = search == null || search.isBlank() ? null : "%" + search.strip().toLowerCase() + "%";
        return jdbc.sql("SELECT " + SUMMARY_COLUMNS + " FROM market_games g"
                + " WHERE (CAST(:term AS text) IS NULL OR lower(g.name) LIKE :term) ORDER BY " + order)
                .param("term", term)
                .query((rs, row) -> summary(rs))
                .list();
    }

    @Transactional(readOnly = true)
    public GameDetail detail(int appid) {
        return jdbc.sql("SELECT " + SUMMARY_COLUMNS + """
                , g.short_description, g.developers, g.release_date, g.review_positive, g.review_negative,
                  g.review_summary, g.tracked_since, g.updated_at,
                  (SELECT s.taken_at FROM market_price_snapshots s WHERE s.steam_appid = g.steam_appid
                   ORDER BY s.final_cents, s.taken_at DESC LIMIT 1) AS lowest_at,
                  (SELECT max(p.players) FROM market_player_snapshots p WHERE p.steam_appid = g.steam_appid) AS peak_all
                FROM market_games g WHERE g.steam_appid = :appid
                """)
                .param("appid", appid)
                .query((rs, row) -> new GameDetail(summary(rs), rs.getString("short_description"),
                        rs.getString("developers"), rs.getString("release_date"),
                        (Integer) rs.getObject("review_positive"), (Integer) rs.getObject("review_negative"),
                        rs.getString("review_summary"), rs.getObject("lowest_at", OffsetDateTime.class),
                        (Integer) rs.getObject("peak_all"), rs.getObject("tracked_since", OffsetDateTime.class),
                        rs.getObject("updated_at", OffsetDateTime.class)))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Jogo não encontrado"));
    }

    @Transactional(readOnly = true)
    public History history(int appid, String range) {
        String key = RANGES.containsKey(range) ? range : "30d";
        String[] window = RANGES.get(key);
        detail(appid); // 404 se o jogo não existe

        // O último preço antes do intervalo abre a linha, para o gráfico não começar vazio
        List<PricePoint> prices = jdbc.sql("""
                (SELECT taken_at, initial_cents, final_cents, discount_percent FROM market_price_snapshots
                 WHERE steam_appid = :appid AND taken_at <= now() - CAST(:window AS interval)
                 ORDER BY taken_at DESC LIMIT 1)
                UNION ALL
                (SELECT taken_at, initial_cents, final_cents, discount_percent FROM market_price_snapshots
                 WHERE steam_appid = :appid AND taken_at > now() - CAST(:window AS interval))
                ORDER BY taken_at
                """)
                .param("appid", appid).param("window", window[0])
                .query((rs, row) -> new PricePoint(rs.getObject("taken_at", OffsetDateTime.class),
                        rs.getInt("initial_cents"), rs.getInt("final_cents"), rs.getInt("discount_percent")))
                .list();

        List<PlayerPoint> players = jdbc.sql("""
                SELECT date_trunc(:bucket, taken_at) AS bucket, max(players) AS players FROM market_player_snapshots
                WHERE steam_appid = :appid AND taken_at > now() - CAST(:window AS interval)
                GROUP BY bucket ORDER BY bucket
                """)
                .param("appid", appid).param("window", window[0]).param("bucket", window[1])
                .query((rs, row) -> new PlayerPoint(rs.getObject("bucket", OffsetDateTime.class), rs.getInt("players")))
                .list();

        return new History(key, prices, players);
    }

    private static GameSummary summary(ResultSet rs) throws SQLException {
        String genres = rs.getString("genres");
        return new GameSummary(rs.getInt("steam_appid"), rs.getString("name"), rs.getString("header_image"),
                genres == null || genres.isEmpty() ? List.of() : Arrays.asList(genres.split("\\|")),
                rs.getBoolean("is_free"), rs.getString("currency"), (Integer) rs.getObject("initial_cents"),
                (Integer) rs.getObject("final_cents"), rs.getInt("discount_percent"),
                (Integer) rs.getObject("current_players"), (Integer) rs.getObject("peak_24h"),
                (Integer) rs.getObject("review_percent"), (Integer) rs.getObject("lowest_cents"));
    }
}
