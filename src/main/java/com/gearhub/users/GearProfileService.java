package com.gearhub.users;

import com.gearhub.config.FieldValidationException;
import com.gearhub.reviews.ContentImageService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
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
    private static final int MAX_SETTING_VALUE = 40;
    private static final int GEAR_OPTIONS_LIMIT = 30;
    public static final int MAX_SETUP_PHOTOS = 12;
    private static final Pattern NUMBER = Pattern.compile("-?\\d{1,7}([.,]\\d{1,6})?");
    /** Equipamentos que a pessoa pode apontar em um jogo, na ordem de exibição. */
    private static final List<String> GEAR_CATEGORIES =
            List.of("mouse", "teclado", "headset", "fone-intra", "mousepad", "monitor", "microfone");

    public record SetupItem(String key, String label, String section, String value) {
    }

    public record SetupPhotos(List<String> photos) {
    }

    public record Game(String slug, String name, String genre) {
    }

    public record GameField(String key, String label, String kind, String unit, List<String> options) {
    }

    public record GameSection(String key, String label, List<GameField> fields) {
    }

    public record GearSlot(String slug, String name) {
    }

    @Schema(description = "Campos que um jogo pede, agrupados, e as categorias de equipamento")
    public record GameDefinition(String slug, String name, String genre, List<GameSection> sections,
            List<GearSlot> gearSlots) {
    }

    public record ConfigValue(String key, String label, String value, String unit) {
    }

    public record ConfigSection(String key, String label, List<ConfigValue> items) {
    }

    @Schema(description = "Produto cadastrado que a pessoa usa no jogo")
    public record GearItem(String category, String categoryName, UUID productId, String slug, String name,
            String brand, String imageUrl, BigDecimal avgScore, int reviewCount, UUID reviewId, String reviewTitle,
            String reviewAuthor) {
    }

    @Schema(description = "Opção do seletor: uma review, ou o produto quando ele ainda não tem reviews")
    public record GearOption(UUID productId, String slug, String name, String brand, String imageUrl, UUID reviewId,
            String reviewTitle, String reviewAuthor, int likeCount) {
    }

    public record GearChoice(UUID productId, UUID reviewId) {
    }

    @Schema(description = "`sections` traz só o que foi preenchido; `settings` traz os valores para edição")
    public record GameConfig(String slug, String name, String genre, boolean selectable, List<ConfigSection> sections,
            List<GearItem> gear, Map<String, String> settings) {
    }

    @Schema(description = "Valores por chave de campo e, em `gear`, o produto e a review escolhidos por categoria")
    public record SaveGame(Map<String, String> settings, Map<String, GearChoice> gear) {
    }

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final ContentImageService images;

    public GearProfileService(JdbcClient jdbc, ObjectMapper json, ContentImageService images) {
        this.jdbc = jdbc;
        this.json = json;
        this.images = images;
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

    @Transactional(readOnly = true)
    public List<String> setupPhotos(String username) {
        String photos = jdbc.sql("SELECT setup_photos::text FROM users WHERE username = :username AND deleted_at IS NULL")
                .param("username", username).query(String.class).optional()
                .orElseThrow(GearProfileService::userNotFound);
        return json.readValue(photos, new TypeReference<List<String>>() {
        });
    }

    @Transactional
    public void saveSetupPhotos(UUID userId, SetupPhotos request) {
        List<String> sent = request.photos() == null ? List.of() : request.photos();
        List<String> photos = sent.stream().filter(url -> url != null && !url.isBlank()).distinct().toList();
        if (photos.size() > MAX_SETUP_PHOTOS) {
            throw new FieldValidationException(Map.of("photos", "Envie até 12 fotos"));
        }
        // Só arquivos enviados ao próprio site: um endereço externo revelaria quem vê o perfil
        if (!photos.stream().allMatch(images::isOwnFile)) {
            throw new FieldValidationException(Map.of("photos", "Envie as fotos pelo site"));
        }
        jdbc.sql("UPDATE users SET setup_photos = cast(:photos AS jsonb), updated_at = now() WHERE id = :id")
                .param("photos", json.writeValueAsString(photos))
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
        return jdbc.sql("SELECT slug, name, genre FROM games WHERE selectable ORDER BY position")
                .query((rs, row) -> new Game(rs.getString("slug"), rs.getString("name"), rs.getString("genre"))).list();
    }

    @Transactional(readOnly = true)
    public GameDefinition game(String slug) {
        return jdbc.sql("SELECT id, slug, name, genre FROM games WHERE slug = :slug AND selectable")
                .param("slug", slug)
                .query((rs, row) -> new GameDefinition(rs.getString("slug"), rs.getString("name"), rs.getString("genre"),
                        sectionsOf(rs.getInt("id")), gearSlots()))
                .optional().orElseThrow(GearProfileService::gameNotFound);
    }

    private record FieldRow(String section, String sectionLabel, GameField field) {
    }

    private List<FieldRow> fieldRows(int gameId) {
        return jdbc.sql("""
                SELECT f.key, f.label, f.kind, f.unit, f.options::text AS options, f.section, s.label AS section_label
                FROM game_fields f JOIN game_sections s ON s.key = f.section
                WHERE f.game_id = :game ORDER BY s.position, f.position
                """)
                .param("game", gameId)
                .query((rs, row) -> new FieldRow(rs.getString("section"), rs.getString("section_label"),
                        new GameField(rs.getString("key"), rs.getString("label"), rs.getString("kind"), rs.getString("unit"),
                                json.readValue(rs.getString("options"), new TypeReference<List<String>>() {
                                }))))
                .list();
    }

    private List<GameSection> sectionsOf(int gameId) {
        Map<String, GameSection> sections = new LinkedHashMap<>();
        for (FieldRow row : fieldRows(gameId)) {
            sections.computeIfAbsent(row.section(), key -> new GameSection(key, row.sectionLabel(), new ArrayList<>()))
                    .fields().add(row.field());
        }
        return List.copyOf(sections.values());
    }

    private List<GearSlot> gearSlots() {
        return jdbc.sql("""
                SELECT slug, name FROM categories WHERE slug IN (:slugs)
                ORDER BY array_position(cast(:order AS text[]), slug)
                """)
                .param("slugs", GEAR_CATEGORIES)
                .param("order", "{" + String.join(",", GEAR_CATEGORIES) + "}")
                .query((rs, row) -> new GearSlot(rs.getString("slug"), rs.getString("name"))).list();
    }

    @Transactional(readOnly = true)
    public List<GameConfig> userGames(String username) {
        UUID userId = jdbc.sql("SELECT id FROM users WHERE username = :username AND deleted_at IS NULL")
                .param("username", username).query(UUID.class).optional()
                .orElseThrow(GearProfileService::userNotFound);
        return jdbc.sql("""
                SELECT g.id, g.slug, g.name, g.genre, g.selectable, u.settings::text AS settings
                FROM user_games u JOIN games g ON g.id = u.game_id
                WHERE u.user_id = :user ORDER BY u.created_at
                """)
                .param("user", userId)
                .query((rs, row) -> {
                    int gameId = rs.getInt("id");
                    Map<String, String> settings = json.readValue(rs.getString("settings"),
                            new TypeReference<Map<String, String>>() {
                            });
                    return new GameConfig(rs.getString("slug"), rs.getString("name"), rs.getString("genre"),
                            rs.getBoolean("selectable"), filledSections(gameId, settings), gearOf(userId, gameId), settings);
                })
                .list();
    }

    private List<ConfigSection> filledSections(int gameId, Map<String, String> settings) {
        Map<String, ConfigSection> sections = new LinkedHashMap<>();
        for (FieldRow row : fieldRows(gameId)) {
            GameField field = row.field();
            String value = "computed".equals(field.kind()) ? edpi(settings) : settings.get(field.key());
            if (value != null) {
                sections.computeIfAbsent(row.section(), key -> new ConfigSection(key, row.sectionLabel(), new ArrayList<>()))
                        .items().add(new ConfigValue(field.key(), field.label(), value, field.unit()));
            }
        }
        return List.copyOf(sections.values());
    }

    private static String edpi(Map<String, String> settings) {
        try {
            String dpi = settings.get("dpi");
            String sensitivity = settings.get("sensitivity");
            if (dpi == null || sensitivity == null) {
                return null;
            }
            return new BigDecimal(dpi).multiply(new BigDecimal(sensitivity)).setScale(2, RoundingMode.HALF_UP)
                    .stripTrailingZeros().toPlainString();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private List<GearItem> gearOf(UUID userId, int gameId) {
        return jdbc.sql("""
                SELECT c.slug AS category, c.name AS category_name, p.id, p.slug, p.name, p.brand, p.image_url,
                       p.avg_score, p.review_count, r.id AS review_id, r.title AS review_title,
                       u.display_name AS review_author
                FROM user_game_gear g
                JOIN products p ON p.id = g.product_id
                JOIN categories c ON c.id = g.category_id
                LEFT JOIN reviews r ON r.id = g.review_id AND r.deleted_at IS NULL
                LEFT JOIN users u ON u.id = r.user_id
                WHERE g.user_id = :user AND g.game_id = :game
                ORDER BY c.position
                """)
                .param("user", userId).param("game", gameId)
                .query((rs, row) -> new GearItem(rs.getString("category"), rs.getString("category_name"),
                        rs.getObject("id", UUID.class), rs.getString("slug"), rs.getString("name"), rs.getString("brand"),
                        rs.getString("image_url"), rs.getBigDecimal("avg_score"), rs.getInt("review_count"),
                        rs.getObject("review_id", UUID.class), rs.getString("review_title"), rs.getString("review_author")))
                .list();
    }

    @Transactional(readOnly = true)
    public List<GearOption> gearOptions(String category, String term) {
        if (!GEAR_CATEGORIES.contains(category)) {
            throw new FieldValidationException(Map.of("category", "Categoria inválida"));
        }
        String clean = term == null ? "" : term.strip();
        // % e _ digitados são texto comum, não curingas
        String like = "%" + clean.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return jdbc.sql("""
                SELECT p.id, p.slug, p.name, p.brand, p.image_url, r.id AS review_id, r.title,
                       u.display_name, r.like_count
                FROM products p
                JOIN categories c ON c.id = p.category_id
                LEFT JOIN reviews r ON r.product_id = p.id AND r.deleted_at IS NULL
                LEFT JOIN users u ON u.id = r.user_id
                WHERE c.slug = :category AND (p.brand || ' ' || p.name) ILIKE :like
                ORDER BY r.like_count DESC NULLS LAST, r.comment_count DESC NULLS LAST, p.brand, p.name
                LIMIT :limit
                """)
                .param("category", category).param("like", like).param("limit", GEAR_OPTIONS_LIMIT)
                .query((rs, row) -> new GearOption(rs.getObject("id", UUID.class), rs.getString("slug"), rs.getString("name"),
                        rs.getString("brand"), rs.getString("image_url"), rs.getObject("review_id", UUID.class),
                        rs.getString("title"), rs.getString("display_name"), rs.getInt("like_count")))
                .list();
    }

    @Transactional
    public void saveGame(UUID userId, String slug, SaveGame request) {
        Integer gameId = jdbc.sql("SELECT id FROM games WHERE slug = :slug AND selectable")
                .param("slug", slug).query(Integer.class).optional()
                .orElseThrow(GearProfileService::gameNotFound);
        Map<String, String> errors = new HashMap<>();
        Map<String, String> settings = validSettings(gameId, request.settings(), errors);
        Map<Integer, GearChoice> gear = validGear(request.gear(), errors);
        if (!errors.isEmpty()) {
            throw new FieldValidationException(errors);
        }
        // Serializa por pessoa: dois envios ao mesmo tempo não passam do limite
        jdbc.sql("SELECT id FROM users WHERE id = :id FOR UPDATE").param("id", userId).query(UUID.class).single();
        long others = jdbc.sql("SELECT count(*) FROM user_games WHERE user_id = :user AND game_id <> :game")
                .param("user", userId).param("game", gameId).query(Long.class).single();
        if (others >= MAX_GAMES) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Você pode cadastrar até 3 jogos");
        }
        jdbc.sql("""
                INSERT INTO user_games (user_id, game_id, settings) VALUES (:user, :game, cast(:settings AS jsonb))
                ON CONFLICT (user_id, game_id) DO UPDATE SET settings = cast(:settings AS jsonb)
                """)
                .param("user", userId)
                .param("game", gameId)
                .param("settings", json.writeValueAsString(settings))
                .update();
        jdbc.sql("DELETE FROM user_game_gear WHERE user_id = :user AND game_id = :game")
                .param("user", userId).param("game", gameId).update();
        gear.forEach((categoryId, choice) -> jdbc.sql("""
                INSERT INTO user_game_gear (user_id, game_id, category_id, product_id, review_id)
                VALUES (:user, :game, :category, :product, :review)
                """)
                .param("user", userId).param("game", gameId).param("category", categoryId)
                .param("product", choice.productId()).param("review", choice.reviewId(), Types.OTHER)
                .update());
    }

    private Map<String, String> validSettings(int gameId, Map<String, String> sent, Map<String, String> errors) {
        Map<String, String> received = sent == null ? Map.of() : sent;
        Map<String, GameField> fields = new HashMap<>();
        fieldRows(gameId).forEach(row -> fields.put(row.field().key(), row.field()));
        Map<String, String> clean = new HashMap<>();
        received.forEach((key, raw) -> {
            GameField field = fields.get(key);
            String value = raw == null ? "" : raw.strip();
            if (field == null) {
                errors.put("settings." + key, "Campo inválido para este jogo");
            } else if (value.isEmpty() || "computed".equals(field.kind())) {
                return;
            } else if (value.length() > MAX_SETTING_VALUE) {
                errors.put("settings." + key, "Use no máximo 40 caracteres");
            } else if ("number".equals(field.kind()) && !isNumber(value)) {
                errors.put("settings." + key, "Informe um número");
            } else if ("choice".equals(field.kind()) && !field.options().contains(value)) {
                errors.put("settings." + key, "Opção inválida");
            } else {
                clean.put(key, "number".equals(field.kind()) ? value.replace(',', '.') : value);
            }
        });
        return clean;
    }

    private static boolean isNumber(String value) {
        return NUMBER.matcher(value).matches();
    }

    private Map<Integer, GearChoice> validGear(Map<String, GearChoice> sent, Map<String, String> errors) {
        Map<Integer, GearChoice> gear = new HashMap<>();
        if (sent == null) {
            return gear;
        }
        sent.forEach((category, choice) -> {
            if (choice == null || choice.productId() == null) {
                return;
            }
            if (!GEAR_CATEGORIES.contains(category)) {
                errors.put("gear." + category, "Categoria inválida");
                return;
            }
            jdbc.sql("""
                    SELECT c.id FROM products p JOIN categories c ON c.id = p.category_id
                    WHERE p.id = :product AND c.slug = :category
                    """)
                    .param("product", choice.productId()).param("category", category)
                    .query(Integer.class).optional()
                    .ifPresentOrElse(categoryId -> {
                        if (choice.reviewId() != null && !isReviewOf(choice)) {
                            errors.put("gear." + category, "Essa review não é deste produto");
                        } else {
                            gear.put(categoryId, choice);
                        }
                    }, () -> errors.put("gear." + category, "Produto não encontrado nesta categoria"));
        });
        return gear;
    }

    @Transactional
    public void removeGame(UUID userId, String slug) {
        jdbc.sql("""
                DELETE FROM user_games WHERE user_id = :user
                AND game_id = (SELECT id FROM games WHERE slug = :slug)
                """)
                .param("user", userId).param("slug", slug).update();
    }

    private boolean isReviewOf(GearChoice choice) {
        return jdbc.sql("SELECT count(*) FROM reviews WHERE id = :review AND product_id = :product AND deleted_at IS NULL")
                .param("review", choice.reviewId()).param("product", choice.productId())
                .query(Long.class).single() > 0;
    }

    private static ResponseStatusException gameNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Jogo não encontrado");
    }

    private static ResponseStatusException userNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuário não encontrado");
    }
}
