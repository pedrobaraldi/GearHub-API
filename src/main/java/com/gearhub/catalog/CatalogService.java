package com.gearhub.catalog;

import com.gearhub.catalog.CatalogResponses.GameCard;
import com.gearhub.catalog.CatalogResponses.GamePage;
import com.gearhub.catalog.CatalogResponses.GameResponse;
import com.gearhub.catalog.IgdbClient.IgdbUnavailableException;
import com.gearhub.market.MarketService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/**
 * Catálogo de jogos com dados do IGDB. A ficha fica guardada no banco por alguns dias: a página abre rápido,
 * respeita o limite de chamadas do IGDB e continua no ar (com a última versão) se ele cair.
 */
@Service
public class CatalogService {

    private static final Logger log = LoggerFactory.getLogger(CatalogService.class);
    private static final Duration FRESH_FOR = Duration.ofDays(3);
    private static final int SEARCH_LIMIT = 20;
    private static final int STEAM_BATCH = 100;
    private static final String UNAVAILABLE = "O catálogo de jogos está indisponível agora. Tente de novo em instantes.";
    private static final String NOT_FOUND = "Jogo não encontrado";

    private final IgdbClient igdb;
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final MarketService market;
    private final Cache<String, List<GameCard>> searches = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(10))
            .maximumSize(1_000)
            .build();

    public CatalogService(IgdbClient igdb, JdbcClient jdbc, ObjectMapper json, MarketService market) {
        this.igdb = igdb;
        this.jdbc = jdbc;
        this.json = json;
        this.market = market;
    }

    /** Busca no catálogo inteiro do IGDB, só jogos jogáveis (sem DLC, pacote ou mod). */
    public List<GameCard> search(String term) {
        String clean = IgdbMapper.searchTerm(term);
        if (clean.length() < 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Digite ao menos 2 caracteres");
        }
        if (clean.length() > 80) {
            clean = clean.substring(0, 80);
        }
        String key = clean.toLowerCase(Locale.ROOT);
        List<GameCard> cached = searches.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        try {
            List<GameCard> found = igdb.query("games", "search \"" + clean + "\"; " + IgdbMapper.CARD_FIELDS
                            + " where " + IgdbMapper.PLAYABLE_TYPES + "; limit " + SEARCH_LIMIT + ";")
                    .stream()
                    .filter(game -> IgdbMapper.validSlug(IgdbMapper.text(game, "slug")) && IgdbMapper.text(game, "name") != null)
                    .map(IgdbMapper::card)
                    .toList();
            searches.put(key, found);
            return found;
        } catch (IgdbUnavailableException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE);
        }
    }

    /** Página do jogo. Vem do banco se estiver recente; senão do IGDB, e a cópia antiga serve se ele falhar. */
    public GameResponse game(String slug) {
        if (!IgdbMapper.validSlug(slug)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, NOT_FOUND);
        }
        Optional<Stored> stored = stored(slug);
        GamePage page;
        if (stored.isPresent() && stored.get().syncedAt().isAfter(OffsetDateTime.now().minus(FRESH_FOR))) {
            page = stored.get().page();
        } else {
            try {
                page = fetch(slug).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, NOT_FOUND));
            } catch (IgdbUnavailableException e) {
                page = stored.map(Stored::page)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE));
            }
        }
        MarketService.GameSummary steam = page.steamAppid() == null ? null : market.summary(page.steamAppid()).orElse(null);
        return new GameResponse(page, steam);
    }

    /** Endereço antigo da aba Games (por appid da Steam): descobre a página do jogo no catálogo. */
    public String slugForSteam(int appid) {
        Optional<String> known = jdbc.sql("""
                SELECT igdb_slug FROM market_games WHERE steam_appid = :appid AND igdb_slug IS NOT NULL
                UNION ALL
                SELECT slug FROM catalog_games WHERE steam_appid = :appid
                """).param("appid", appid).query(String.class).list().stream().findFirst();
        if (known.isPresent()) {
            return known.get();
        }
        try {
            return steamSlugs(List.of(appid)).getOrDefault(appid, null);
        } catch (IgdbUnavailableException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE);
        }
    }

    /** Liga os jogos acompanhados na Steam às suas páginas no catálogo. Roda junto com a coleta. */
    public int linkMarketGames() {
        if (!igdb.configured()) {
            return 0;
        }
        List<Integer> pending = jdbc.sql("SELECT steam_appid FROM market_games WHERE igdb_slug IS NULL ORDER BY steam_appid")
                .query(Integer.class).list();
        int linked = 0;
        for (int start = 0; start < pending.size(); start += STEAM_BATCH) {
            List<Integer> batch = pending.subList(start, Math.min(pending.size(), start + STEAM_BATCH));
            try {
                for (Map.Entry<Integer, String> entry : steamSlugs(batch).entrySet()) {
                    linked += jdbc.sql("UPDATE market_games SET igdb_slug = :slug WHERE steam_appid = :appid")
                            .param("slug", entry.getValue()).param("appid", entry.getKey()).update();
                }
            } catch (IgdbUnavailableException e) {
                log.warn("Não consegui ligar os jogos da Steam ao IGDB: {}", e.getMessage());
                return linked;
            }
        }
        return linked;
    }

    private Map<Integer, String> steamSlugs(List<Integer> appids) {
        String uids = appids.stream().map(appid -> "\"" + appid + "\"").collect(Collectors.joining(","));
        return igdb.query("external_games", "fields uid,game.slug; where external_game_source = "
                        + IgdbMapper.STEAM_SOURCE + " & uid = (" + uids + "); limit 500;")
                .stream()
                .filter(row -> IgdbMapper.text(row, "uid") != null && IgdbMapper.text(row, "uid").matches("\\d{1,9}"))
                .filter(row -> IgdbMapper.validSlug(IgdbMapper.text(IgdbMapper.object(row, "game"), "slug")))
                .collect(Collectors.toMap(
                        row -> Integer.valueOf(IgdbMapper.text(row, "uid")),
                        row -> IgdbMapper.text(IgdbMapper.object(row, "game"), "slug"),
                        (first, second) -> first));
    }

    private Optional<GamePage> fetch(String slug) {
        List<Map<String, Object>> rows = igdb.query("games", IgdbMapper.GAME_FIELDS + " where slug = \"" + slug + "\"; limit 1;");
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> game = rows.getFirst();
        Object id = game.get("id");
        Map<String, Object> timeToBeat = id instanceof Number number
                ? igdb.query("game_time_to_beats", "fields hastily,normally,completely,count; where game_id = "
                        + number.intValue() + ";").stream().findFirst().orElse(null)
                : null;
        GamePage page = IgdbMapper.page(game, timeToBeat);
        save(page);
        return Optional.of(page);
    }

    private void save(GamePage page) {
        jdbc.sql("""
                INSERT INTO catalog_games (igdb_id, slug, name, cover_url, first_release_date, total_rating, steam_appid, details, synced_at)
                VALUES (:id, :slug, :name, :cover, :released, :rating, :steam, cast(:details AS jsonb), now())
                ON CONFLICT (igdb_id) DO UPDATE SET slug = excluded.slug, name = excluded.name,
                    cover_url = excluded.cover_url, first_release_date = excluded.first_release_date,
                    total_rating = excluded.total_rating, steam_appid = excluded.steam_appid,
                    details = excluded.details, synced_at = now()
                """)
                .param("id", page.igdbId())
                .param("slug", page.slug())
                .param("name", page.name())
                .param("cover", page.coverUrl())
                .param("released", page.releaseDate() == null ? null : Date.valueOf(page.releaseDate()))
                .param("rating", page.ratings().total() == null ? null : BigDecimal.valueOf(page.ratings().total()))
                .param("steam", page.steamAppid())
                .param("details", json.writeValueAsString(page))
                .update();
        if (page.steamAppid() != null) {
            jdbc.sql("UPDATE market_games SET igdb_slug = :slug WHERE steam_appid = :appid AND igdb_slug IS NULL")
                    .param("slug", page.slug()).param("appid", page.steamAppid()).update();
        }
    }

    private record Stored(GamePage page, OffsetDateTime syncedAt) {
    }

    private Optional<Stored> stored(String slug) {
        return jdbc.sql("SELECT details::text AS details, synced_at FROM catalog_games WHERE slug = :slug")
                .param("slug", slug)
                .query((rs, row) -> new Stored(json.readValue(rs.getString("details"), GamePage.class),
                        rs.getObject("synced_at", OffsetDateTime.class)))
                .optional();
    }
}
