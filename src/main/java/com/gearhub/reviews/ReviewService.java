package com.gearhub.reviews;

import com.gearhub.admin.AuditService;
import com.gearhub.catalog.CatalogResponses;
import com.gearhub.config.FieldValidationException;
import com.gearhub.reviews.ContentResponses.ReviewDraft;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Types;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Reviews: publicar, editar, apagar e abrir para edição. As notas e a ficha técnica são conferidas contra os
 * critérios e campos da categoria do produto.
 */
@Service
public class ReviewService {

    private static final BigDecimal MAX_SCORE = BigDecimal.TEN;

    /** Categoria das reviews de jogo: o produto é o próprio jogo do catálogo. */
    static final String GAMES_CATEGORY = "jogos";

    private final JdbcClient jdbc;
    private final Publications publications;
    private final ProductService products;
    private final AuditService audit;

    public ReviewService(JdbcClient jdbc, Publications publications, ProductService products, AuditService audit) {
        this.jdbc = jdbc;
        this.publications = publications;
        this.products = products;
        this.audit = audit;
    }

    @Transactional
    public UUID create(UUID userId, ContentRequests.CreateReview request) {
        UUID productId = request.productId() != null ? request.productId()
                : request.game() != null && !request.game().isBlank() ? gameProduct(request.game())
                : findOrCreateProduct(request);
        Map<String, Object> product = jdbc.sql("SELECT category_id, image_url FROM products WHERE id = :id")
                .param("id", productId)
                .query().listOfRows().stream().findFirst()
                .orElseThrow(() -> new FieldValidationException(Map.of("productId", "Produto não encontrado")));

        ValidatedReview valid = validate(((Number) product.get("category_id")).intValue(), request.scores(), request.specs());
        List<String> sentImages = publications.validImages(request.images(), List.of());
        UUID reviewId;
        try {
            reviewId = jdbc.sql("""
                    INSERT INTO reviews (user_id, product_id, title, body, score, cover_url, images, specs)
                    VALUES (:user, :product, :title, :body, :score, :cover, cast(:images AS jsonb),
                            cast(:specs AS jsonb)) RETURNING id
                    """)
                    .param("user", userId)
                    .param("product", productId)
                    .param("title", blankToNull(request.title()), Types.VARCHAR)
                    .param("body", blankToNull(request.body()), Types.VARCHAR)
                    .param("score", valid.average(), Types.NUMERIC)
                    .param("cover", sentImages.isEmpty() ? product.get("image_url") : sentImages.getFirst(), Types.VARCHAR)
                    .param("images", publications.toJson(sentImages))
                    .param("specs", publications.toJson(valid.specs()))
                    .query(UUID.class).single();
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Você já publicou uma review desse produto");
        }
        writeDetails(reviewId, valid.scores(), request.pros(), request.cons());
        products.refreshScore(productId);
        audit.record(userId, "review.create", "review", reviewId,
                Map.of("text", AuditService.snippet(request.title() != null ? request.title() : request.body())));
        return reviewId;
    }

    @Transactional
    public void update(UUID userId, UUID reviewId, ContentRequests.UpdateReview request) {
        publications.requireOwner(ContentKind.REVIEW, reviewId, userId);
        Map<String, Object> before = publications.snapshot(ContentKind.REVIEW, reviewId);
        Map<String, Object> current = jdbc.sql("""
                SELECT r.product_id, r.images::text AS images, p.category_id, p.image_url FROM reviews r
                JOIN products p ON p.id = r.product_id WHERE r.id = :id
                """).param("id", reviewId).query().singleRow();

        ValidatedReview valid = validate(((Number) current.get("category_id")).intValue(), request.scores(), request.specs());
        List<String> sentImages = publications.validImages(request.images(),
                publications.parseList((String) current.get("images")));
        jdbc.sql("""
                UPDATE reviews SET title = :title, body = :body, score = :score, cover_url = :cover,
                       images = cast(:images AS jsonb), specs = cast(:specs AS jsonb), updated_at = now()
                WHERE id = :id
                """)
                .param("title", blankToNull(request.title()), Types.VARCHAR)
                .param("body", blankToNull(request.body()), Types.VARCHAR)
                .param("score", valid.average(), Types.NUMERIC)
                .param("cover", sentImages.isEmpty() ? current.get("image_url") : sentImages.getFirst(), Types.VARCHAR)
                .param("images", publications.toJson(sentImages))
                .param("specs", publications.toJson(valid.specs()))
                .param("id", reviewId)
                .update();
        jdbc.sql("DELETE FROM review_scores WHERE review_id = :id").param("id", reviewId).update();
        jdbc.sql("DELETE FROM review_pros_cons WHERE review_id = :id").param("id", reviewId).update();
        writeDetails(reviewId, valid.scores(), request.pros(), request.cons());
        products.refreshScore((UUID) current.get("product_id"));
        audit.record(userId, "review.update", "review", reviewId,
                Map.of("before", before, "after", publications.snapshot(ContentKind.REVIEW, reviewId)));
    }

    @Transactional
    public void delete(UUID userId, UUID reviewId) {
        publications.markDeleted(ContentKind.REVIEW, userId, reviewId);
        UUID productId = jdbc.sql("SELECT product_id FROM reviews WHERE id = :id")
                .param("id", reviewId).query(UUID.class).single();
        products.refreshScore(productId);
    }

    @Transactional(readOnly = true)
    public ReviewDraft draft(UUID userId, UUID reviewId) {
        publications.requireOwner(ContentKind.REVIEW, reviewId, userId);
        Map<String, BigDecimal> scores = new LinkedHashMap<>();
        jdbc.sql("SELECT criterion_id, value FROM review_scores WHERE review_id = :id")
                .param("id", reviewId)
                .query(rs -> {
                    scores.put(String.valueOf(rs.getInt("criterion_id")), rs.getBigDecimal("value"));
                });
        return jdbc.sql("""
                SELECT r.product_id, p.brand, p.name, c.slug, r.title, r.body, r.specs::text AS specs,
                       r.images::text AS images, g.slug AS game_slug, g.name AS game_name, g.cover_url AS game_cover
                FROM reviews r
                JOIN products p ON p.id = r.product_id
                JOIN categories c ON c.id = p.category_id
                LEFT JOIN catalog_games g ON g.igdb_id = p.game_id
                WHERE r.id = :id
                """)
                .param("id", reviewId)
                .query((rs, row) -> new ReviewDraft(rs.getObject("product_id", UUID.class),
                        rs.getString("game_slug") != null ? rs.getString("name") : rs.getString("brand") + " " + rs.getString("name"),
                        rs.getString("slug"), Publications.gameRef(rs),
                        rs.getString("title"), rs.getString("body"), scores, publications.parseSpecs(rs.getString("specs")),
                        prosOrCons(reviewId, "pro"), prosOrCons(reviewId, "con"),
                        publications.parseList(rs.getString("images"))))
                .single();
    }

    // Produto ----------------------------------------------------------------

    /**
     * Produto informado por categoria, marca e modelo. Se já existir um igual (sem diferenciar maiúsculas,
     * espaços extras, ou o modelo escrito com a marca na frente), a review entra nele; senão, ele é criado.
     */
    private UUID findOrCreateProduct(ContentRequests.CreateReview request) {
        Map<String, String> errors = new LinkedHashMap<>();
        String brand = tidy(request.brand());
        String model = tidy(request.model());
        Integer categoryId = request.category() == null ? null : jdbc.sql("SELECT id FROM categories WHERE slug = :slug")
                .param("slug", request.category().strip()).query(Integer.class).optional().orElse(null);
        if (categoryId == null) {
            errors.put("category", "Escolha a categoria do produto");
        }
        if (brand.isEmpty()) {
            errors.put("brand", "Informe a marca");
        }
        if (model.isEmpty()) {
            errors.put("model", "Informe o modelo");
        }
        if (!errors.isEmpty()) {
            throw new FieldValidationException(errors);
        }
        // Quem escreve "Logitech G Pro" no modelo também cai no produto "G Pro" da Logitech
        if (model.toLowerCase().startsWith(brand.toLowerCase() + " ")) {
            model = model.substring(brand.length()).strip();
        }

        Optional<UUID> existing = jdbc.sql("""
                SELECT id FROM products
                WHERE category_id = :category
                  AND lower(regexp_replace(brand, '\\s+', ' ', 'g')) = lower(:brand)
                  AND lower(regexp_replace(name, '\\s+', ' ', 'g')) IN (lower(:model), lower(:brand || ' ' || :model))
                ORDER BY review_count DESC LIMIT 1
                """)
                .param("category", categoryId).param("brand", brand).param("model", model)
                .query(UUID.class).optional();
        if (existing.isPresent()) {
            return existing.get();
        }
        return jdbc.sql("INSERT INTO products (slug, name, brand, category_id) VALUES (:slug, :name, :brand, :category) RETURNING id")
                .param("slug", freeSlug(slugOf(brand + " " + model))).param("name", model).param("brand", brand)
                .param("category", categoryId)
                .query(UUID.class).single();
    }

    /** Produto de uma review de jogo: o próprio jogo do catálogo, criado na primeira review. */
    private UUID gameProduct(String slug) {
        CatalogResponses.GamePage game = publications.catalogGame(slug);
        Optional<UUID> existing = jdbc.sql("SELECT id FROM products WHERE game_id = :game")
                .param("game", game.igdbId()).query(UUID.class).optional();
        if (existing.isPresent()) {
            // A capa acompanha o catálogo
            jdbc.sql("UPDATE products SET name = :name, image_url = :cover WHERE id = :id")
                    .param("name", game.name()).param("cover", game.coverUrl(), Types.VARCHAR).param("id", existing.get()).update();
            return existing.get();
        }
        String studio = game.developers().isEmpty() ? "Jogo" : game.developers().getFirst();
        return jdbc.sql("""
                INSERT INTO products (slug, name, brand, category_id, image_url, game_id)
                VALUES (:slug, :name, :brand, (SELECT id FROM categories WHERE slug = :category), :cover, :game)
                RETURNING id
                """)
                .param("slug", freeSlug(game.slug())).param("name", game.name()).param("brand", studio)
                .param("category", GAMES_CATEGORY).param("cover", game.coverUrl(), Types.VARCHAR)
                .param("game", game.igdbId())
                .query(UUID.class).single();
    }

    /** {@code base}, ou {@code base-2}, {@code base-3}... se já estiver em uso. */
    private String freeSlug(String base) {
        String slug = base;
        for (int n = 2; jdbc.sql("SELECT count(*) FROM products WHERE slug = :slug").param("slug", slug)
                .query(Integer.class).single() > 0; n++) {
            slug = base + "-" + n;
        }
        return slug;
    }

    private static String tidy(String text) {
        return text == null ? "" : text.strip().replaceAll("\\s+", " ");
    }

    private static String slugOf(String text) {
        String plain = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        String slug = plain.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return slug.isEmpty() ? "produto" : slug;
    }

    // Notas, ficha, prós e contras ---------------------------------------------

    private record ValidatedReview(Map<Integer, BigDecimal> scores, Map<String, String> specs, BigDecimal average) {
    }

    private ValidatedReview validate(int categoryId, Map<String, BigDecimal> scores, Map<String, String> specs) {
        Map<String, String> errors = new LinkedHashMap<>();
        Map<Integer, BigDecimal> validScores = validateScores(categoryId, scores, errors);
        Map<String, String> validSpecs = validateSpecs(categoryId, specs, errors);
        if (!errors.isEmpty()) {
            throw new FieldValidationException(errors);
        }
        // Sem nenhuma nota, a review fica sem média
        BigDecimal average = validScores.isEmpty() ? null
                : validScores.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(validScores.size()), 1, RoundingMode.HALF_UP);
        return new ValidatedReview(validScores, validSpecs, average);
    }

    private Map<Integer, BigDecimal> validateScores(int categoryId, Map<String, BigDecimal> sent,
            Map<String, String> errors) {
        Map<String, BigDecimal> received = sent == null ? Map.of() : sent;
        Map<Integer, BigDecimal> valid = new LinkedHashMap<>();
        Set<String> known = new HashSet<>();
        jdbc.sql("SELECT id, label FROM criteria WHERE category_id = :category ORDER BY position")
                .param("category", categoryId)
                .query().listOfRows().forEach(row -> {
                    Integer id = ((Number) row.get("id")).intValue();
                    known.add(id.toString());
                    BigDecimal value = received.get(id.toString());
                    if (value == null) {
                        return;
                    }
                    if (value.signum() < 0 || value.compareTo(MAX_SCORE) > 0) {
                        errors.put("scores." + id, "A nota deve ser de 0 a 10");
                    } else {
                        valid.put(id, value.setScale(1, RoundingMode.HALF_UP));
                    }
                });
        received.keySet().stream().filter(key -> !known.contains(key)).forEach(key ->
                errors.put("scores." + key, "Essa nota não pertence à categoria do produto"));
        return valid;
    }

    private Map<String, String> validateSpecs(int categoryId, Map<String, String> sent, Map<String, String> errors) {
        Map<String, String> received = sent == null ? Map.of() : sent;
        Map<String, String> valid = new LinkedHashMap<>();
        jdbc.sql("SELECT key, label, required FROM spec_fields WHERE category_id = :category ORDER BY position")
                .param("category", categoryId)
                .query().listOfRows().forEach(row -> {
                    String key = (String) row.get("key");
                    String value = received.get(key);
                    boolean empty = value == null || value.isBlank();
                    if (empty && Boolean.TRUE.equals(row.get("required"))) {
                        errors.put("specs." + key, "Preencha o campo " + row.get("label"));
                    } else if (!empty && value.length() > 200) {
                        errors.put("specs." + key, "Use no máximo 200 caracteres");
                    } else if (!empty) {
                        valid.put(key, value.strip());
                    }
                });
        return valid;
    }

    private void writeDetails(UUID reviewId, Map<Integer, BigDecimal> scores, List<String> pros, List<String> cons) {
        scores.forEach((criterionId, value) -> jdbc.sql(
                "INSERT INTO review_scores (review_id, criterion_id, value) VALUES (:review, :criterion, :value)")
                .param("review", reviewId).param("criterion", criterionId).param("value", value)
                .update());
        insertProsCons(reviewId, "pro", pros);
        insertProsCons(reviewId, "con", cons);
    }

    private void insertProsCons(UUID reviewId, String kind, List<String> items) {
        if (items == null) {
            return;
        }
        List<String> filled = items.stream().filter(Objects::nonNull).map(String::strip)
                .filter(text -> !text.isEmpty()).toList();
        for (int i = 0; i < filled.size(); i++) {
            jdbc.sql("INSERT INTO review_pros_cons (review_id, kind, text, position) VALUES (:review, :kind, :text, :position)")
                    .param("review", reviewId).param("kind", kind).param("text", filled.get(i)).param("position", i)
                    .update();
        }
    }

    private List<String> prosOrCons(UUID reviewId, String kind) {
        return jdbc.sql("SELECT text FROM review_pros_cons WHERE review_id = :id AND kind = :kind ORDER BY position")
                .param("id", reviewId).param("kind", kind)
                .query(String.class).list();
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }
}
