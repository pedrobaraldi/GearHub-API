package com.gearhub.users;

import com.gearhub.config.FieldValidationException;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Types;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Setup e jogos favoritos exibidos no perfil. */
@Service
public class GearProfileService {

    public static final int MAX_GAMES = 3;
    private static final int MAX_SETUP_VALUE = 120;

    public record SetupItem(String key, String label, String section, String value) {
    }

    public record Game(String slug, String name) {
    }

    public record GameConfig(String slug, String name, Integer dpi, BigDecimal sensitivity, BigDecimal edpi,
            String resolution, String aspectRatio, Integer refreshRate) {
    }

    public record SaveGame(
            @Min(value = 50, message = "O DPI deve ser de 50 a 100000")
            @Max(value = 100000, message = "O DPI deve ser de 50 a 100000")
            Integer dpi,

            @DecimalMin(value = "0", message = "A sensibilidade deve ser de 0 a 1000")
            @DecimalMax(value = "1000", message = "A sensibilidade deve ser de 0 a 1000")
            BigDecimal sensitivity,

            @Size(max = 20, message = "Use no máximo 20 caracteres")
            String resolution,

            @Size(max = 10, message = "Use no máximo 10 caracteres")
            String aspectRatio,

            @Min(value = 1, message = "A taxa de atualização deve ser de 1 a 1000 Hz")
            @Max(value = 1000, message = "A taxa de atualização deve ser de 1 a 1000 Hz")
            Integer refreshRate) {
    }

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public GearProfileService(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // Setup ----------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<SetupItem> mySetup(UUID userId) {
        String setup = jdbc.sql("SELECT setup::text FROM users WHERE id = :id")
                .param("id", userId).query(String.class).single();
        return merged(setup);
    }

    @Transactional(readOnly = true)
    public List<SetupItem> publicSetup(String username) {
        String setup = jdbc.sql("SELECT setup::text FROM users WHERE username = :username AND deleted_at IS NULL")
                .param("username", username).query(String.class).optional()
                .orElseThrow(GearProfileService::userNotFound);
        return merged(setup).stream().filter(item -> item.value() != null).toList();
    }

    @Transactional
    public void saveSetup(UUID userId, Map<String, String> values) {
        Map<String, String> sent = values == null ? Map.of() : values;
        Map<String, String> clean = new HashMap<>();
        Map<String, String> errors = new HashMap<>();
        List<String> known = jdbc.sql("SELECT key FROM setup_slots").query(String.class).list();
        sent.forEach((key, value) -> {
            if (!known.contains(key)) {
                errors.put(key, "Campo de setup inválido");
            } else if (value != null && value.length() > MAX_SETUP_VALUE) {
                errors.put(key, "Use no máximo 120 caracteres");
            } else if (value != null && !value.isBlank()) {
                clean.put(key, value.strip());
            }
        });
        if (!errors.isEmpty()) {
            throw new FieldValidationException(errors);
        }
        jdbc.sql("UPDATE users SET setup = cast(:setup AS jsonb), updated_at = now() WHERE id = :id")
                .param("setup", json.writeValueAsString(clean))
                .param("id", userId)
                .update();
    }

    private List<SetupItem> merged(String setupJson) {
        Map<String, String> values = json.readValue(setupJson, new TypeReference<Map<String, String>>() {
        });
        return jdbc.sql("SELECT key, label, section FROM setup_slots ORDER BY position")
                .query((rs, row) -> new SetupItem(rs.getString("key"), rs.getString("label"), rs.getString("section"),
                        values.get(rs.getString("key"))))
                .list();
    }

    // Jogos ----------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Game> games() {
        return jdbc.sql("SELECT slug, name FROM games ORDER BY position")
                .query((rs, row) -> new Game(rs.getString("slug"), rs.getString("name"))).list();
    }

    @Transactional(readOnly = true)
    public List<GameConfig> userGames(String username) {
        UUID userId = jdbc.sql("SELECT id FROM users WHERE username = :username AND deleted_at IS NULL")
                .param("username", username).query(UUID.class).optional()
                .orElseThrow(GearProfileService::userNotFound);
        return jdbc.sql("""
                SELECT g.slug, g.name, u.dpi, u.sensitivity, u.resolution, u.aspect_ratio, u.refresh_rate
                FROM user_games u JOIN games g ON g.id = u.game_id
                WHERE u.user_id = :user ORDER BY u.created_at
                """)
                .param("user", userId)
                .query((rs, row) -> {
                    Integer dpi = rs.getObject("dpi", Integer.class);
                    BigDecimal sensitivity = rs.getBigDecimal("sensitivity");
                    BigDecimal edpi = dpi == null || sensitivity == null ? null
                            : sensitivity.multiply(BigDecimal.valueOf(dpi)).setScale(1, RoundingMode.HALF_UP);
                    return new GameConfig(rs.getString("slug"), rs.getString("name"), dpi, sensitivity, edpi,
                            rs.getString("resolution"), rs.getString("aspect_ratio"),
                            rs.getObject("refresh_rate", Integer.class));
                })
                .list();
    }

    @Transactional
    public void saveGame(UUID userId, String slug, SaveGame request) {
        Integer gameId = jdbc.sql("SELECT id FROM games WHERE slug = :slug")
                .param("slug", slug).query(Integer.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Jogo não encontrado"));
        // Serializa por pessoa: dois envios ao mesmo tempo não passam do limite
        jdbc.sql("SELECT id FROM users WHERE id = :id FOR UPDATE").param("id", userId).query(UUID.class).single();
        long others = jdbc.sql("SELECT count(*) FROM user_games WHERE user_id = :user AND game_id <> :game")
                .param("user", userId).param("game", gameId).query(Long.class).single();
        if (others >= MAX_GAMES) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Você pode cadastrar até 3 jogos");
        }
        jdbc.sql("""
                INSERT INTO user_games (user_id, game_id, dpi, sensitivity, resolution, aspect_ratio, refresh_rate)
                VALUES (:user, :game, :dpi, :sensitivity, :resolution, :ratio, :refresh)
                ON CONFLICT (user_id, game_id) DO UPDATE
                SET dpi = :dpi, sensitivity = :sensitivity, resolution = :resolution,
                    aspect_ratio = :ratio, refresh_rate = :refresh
                """)
                .param("user", userId)
                .param("game", gameId)
                .param("dpi", request.dpi(), Types.INTEGER)
                .param("sensitivity", request.sensitivity(), Types.NUMERIC)
                .param("resolution", blankToNull(request.resolution()), Types.VARCHAR)
                .param("ratio", blankToNull(request.aspectRatio()), Types.VARCHAR)
                .param("refresh", request.refreshRate(), Types.INTEGER)
                .update();
    }

    @Transactional
    public void removeGame(UUID userId, String slug) {
        jdbc.sql("""
                DELETE FROM user_games WHERE user_id = :user
                AND game_id = (SELECT id FROM games WHERE slug = :slug)
                """)
                .param("user", userId).param("slug", slug).update();
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }

    private static ResponseStatusException userNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuário não encontrado");
    }
}
