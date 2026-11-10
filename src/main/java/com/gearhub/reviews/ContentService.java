package com.gearhub.reviews;

import com.gearhub.config.FieldValidationException;
import com.gearhub.reviews.ContentResponses.Author;
import com.gearhub.reviews.ContentResponses.FeedItem;
import com.gearhub.reviews.ContentResponses.FeedPage;
import com.gearhub.reviews.ContentResponses.PostDraft;
import com.gearhub.reviews.ContentResponses.PostTopic;
import com.gearhub.reviews.ContentResponses.ProductRef;
import com.gearhub.reviews.ContentResponses.ProductSummary;
import com.gearhub.reviews.ContentResponses.ReviewDraft;
import com.gearhub.reviews.ContentResponses.SearchResults;
import com.gearhub.reviews.ContentResponses.Score;
import com.gearhub.reviews.ContentResponses.Spec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Posts, reviews e feed. Usa SQL direto porque o feed junta várias tabelas de leitura. */
@Service
public class ContentService {

    private static final int MAX_PAGE_SIZE = 50;
    private static final BigDecimal MAX_SCORE = BigDecimal.TEN;
    private static final int MAX_TAGS = 10;
    private static final int MIN_SEARCH = 2;
    private static final int HALL_REVIEWERS = 20;
    private static final int HALL_SETUPS = 6;
    private static final int HALL_PRODUCTS = 4;
    private static final int SEARCH_LIMIT = 5;
    private static final Pattern TAG = Pattern.compile("[\\p{L}\\p{N}_-]{1,30}");
    private static final Pattern HASHTAG = Pattern.compile("#(" + TAG.pattern() + ")");

    /** Filtros do feed. Campos nulos não filtram. */
    public record FeedQuery(String category, String author, UUID savedBy, Instant before, String text, int size,
            String product) {
    }

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final ContentImageService images;

    public ContentService(JdbcClient jdbc, ObjectMapper json, ContentImageService images) {
        this.jdbc = jdbc;
        this.json = json;
        this.images = images;
    }

    @Transactional(readOnly = true)
    public List<PostTopic> postTopics() {
        return jdbc.sql("SELECT slug, name, emoji FROM post_topics ORDER BY position")
                .query((rs, row) -> new PostTopic(rs.getString("slug"), rs.getString("name"), rs.getString("emoji")))
                .list();
    }

    // Posts ----------------------------------------------------------------

    @Transactional
    public UUID createPost(UUID userId, ContentRequests.SavePost request) {
        return jdbc.sql("""
                INSERT INTO posts (user_id, topic_id, body, images, tags)
                VALUES (:user, :topic, :body, cast(:images AS jsonb), cast(:tags AS jsonb)) RETURNING id
                """)
                .param("user", userId)
                .param("topic", topicIdOf(request.topic()), Types.INTEGER)
                .param("body", request.body().strip())
                .param("images", json.writeValueAsString(validImages(request.images(), List.of())))
                .param("tags", json.writeValueAsString(tagsOf(request)))
                .query(UUID.class).single();
    }

    @Transactional
    public void updatePost(UUID userId, UUID postId, ContentRequests.SavePost request) {
        requireOwner(ContentKind.POST, postId, userId);
        List<String> currentImages = parseList(jdbc.sql("SELECT images::text FROM posts WHERE id = :id")
                .param("id", postId).query(String.class).single());
        jdbc.sql("""
                UPDATE posts SET topic_id = :topic, body = :body, images = cast(:images AS jsonb),
                       tags = cast(:tags AS jsonb), updated_at = now()
                WHERE id = :id
                """)
                .param("topic", topicIdOf(request.topic()), Types.INTEGER)
                .param("body", request.body().strip())
                .param("images", json.writeValueAsString(validImages(request.images(), currentImages)))
                .param("tags", json.writeValueAsString(tagsOf(request)))
                .param("id", postId)
                .update();
    }

    @Transactional(readOnly = true)
    public PostDraft postDraft(UUID userId, UUID postId) {
        requireOwner(ContentKind.POST, postId, userId);
        return jdbc.sql("""
                SELECT p.body, t.slug, p.images::text AS images, p.tags::text AS tags FROM posts p
                LEFT JOIN post_topics t ON t.id = p.topic_id WHERE p.id = :id
                """)
                .param("id", postId)
                .query((rs, row) -> {
                    // Hashtags continuam no texto: o campo de tags só mostra as demais
                    Set<String> inBody = hashtags(rs.getString("body"));
                    List<String> tags = parseList(rs.getString("tags")).stream()
                            .filter(tag -> !inBody.contains(tag)).toList();
                    return new PostDraft(rs.getString("body"), rs.getString("slug"), tags, parseList(rs.getString("images")));
                })
                .single();
    }

    private Integer topicIdOf(String slug) {
        if (slug == null || slug.isBlank()) {
            return null;
        }
        return jdbc.sql("SELECT id FROM post_topics WHERE slug = :slug")
                .param("slug", slug)
                .query(Integer.class).optional()
                .orElseThrow(() -> new FieldValidationException(Map.of("topic", "Tópico não encontrado")));
    }

    private static Set<String> hashtags(String body) {
        Set<String> found = new LinkedHashSet<>();
        Matcher matcher = HASHTAG.matcher(body);
        while (matcher.find()) {
            found.add(matcher.group(1).toLowerCase());
        }
        return found;
    }

    private static List<String> tagsOf(ContentRequests.SavePost request) {
        Set<String> tags = new LinkedHashSet<>();
        if (request.tags() != null) {
            for (String tag : request.tags()) {
                String clean = tag == null ? "" : tag.strip().replaceFirst("^#", "").toLowerCase();
                if (clean.isEmpty()) {
                    continue;
                }
                if (!TAG.matcher(clean).matches()) {
                    throw new FieldValidationException(Map.of("tags", "Use só letras, números, - e _ nas tags"));
                }
                tags.add(clean);
            }
        }
        tags.addAll(hashtags(request.body()));
        return tags.stream().limit(MAX_TAGS).toList();
    }

    /** {@code current} são as imagens já salvas, que podem ser mantidas como estão. */
    private List<String> validImages(List<String> sent, List<String> current) {
        if (sent == null) {
            return List.of();
        }
        List<String> valid = sent.stream().filter(url -> url != null && !url.isBlank()).distinct().toList();
        if (!valid.stream().allMatch(url -> current.contains(url) || images.isOwnFile(url))) {
            throw new FieldValidationException(Map.of("images", "Imagem inválida. Envie o arquivo de novo."));
        }
        return valid;
    }

    private List<String> parseList(String listJson) {
        return json.readValue(listJson, new TypeReference<List<String>>() {
        });
    }

    // Reviews --------------------------------------------------------------

    @Transactional
    public UUID createReview(UUID userId, ContentRequests.CreateReview request) {
        UUID productId = request.productId() != null ? request.productId() : findOrCreateProduct(request);
        Map<String, Object> product = jdbc.sql("SELECT category_id, image_url FROM products WHERE id = :id")
                .param("id", productId)
                .query().listOfRows().stream().findFirst()
                .orElseThrow(() -> new FieldValidationException(Map.of("productId", "Produto não encontrado")));

        ValidatedReview valid = validate(((Number) product.get("category_id")).intValue(), request.scores(), request.specs());
        List<String> sentImages = validImages(request.images(), List.of());
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
                    .param("images", json.writeValueAsString(sentImages))
                    .param("specs", json.writeValueAsString(valid.specs()))
                    .query(UUID.class).single();
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Você já publicou uma review desse produto");
        }
        writeDetails(reviewId, valid.scores(), request.pros(), request.cons());
        refreshProductScore(productId);
        return reviewId;
    }

    /**
     * Produto informado por categoria, marca e modelo. Se já existir um igual (sem diferenciar maiúsculas,
     * espaços extras, ou o modelo escrito com a marca na frente), a review entra nele; senão, ele é criado.
     */
    private UUID findOrCreateProduct(ContentRequests.CreateReview request) {
        Map<String, String> errors = new java.util.LinkedHashMap<>();
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

        String base = slugOf(brand + " " + model);
        String slug = base;
        for (int n = 2; jdbc.sql("SELECT count(*) FROM products WHERE slug = :slug").param("slug", slug)
                .query(Integer.class).single() > 0; n++) {
            slug = base + "-" + n;
        }
        return jdbc.sql("INSERT INTO products (slug, name, brand, category_id) VALUES (:slug, :name, :brand, :category) RETURNING id")
                .param("slug", slug).param("name", model).param("brand", brand).param("category", categoryId)
                .query(UUID.class).single();
    }

    private static String tidy(String text) {
        return text == null ? "" : text.strip().replaceAll("\\s+", " ");
    }

    private static String slugOf(String text) {
        String plain = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        String slug = plain.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return slug.isEmpty() ? "produto" : slug;
    }

    @Transactional
    public void updateReview(UUID userId, UUID reviewId, ContentRequests.UpdateReview request) {
        requireOwner(ContentKind.REVIEW, reviewId, userId);
        Map<String, Object> current = jdbc.sql("""
                SELECT r.product_id, r.images::text AS images, p.category_id, p.image_url FROM reviews r
                JOIN products p ON p.id = r.product_id WHERE r.id = :id
                """).param("id", reviewId).query().singleRow();

        ValidatedReview valid = validate(((Number) current.get("category_id")).intValue(), request.scores(), request.specs());
        List<String> sentImages = validImages(request.images(), parseList((String) current.get("images")));
        jdbc.sql("""
                UPDATE reviews SET title = :title, body = :body, score = :score, cover_url = :cover,
                       images = cast(:images AS jsonb), specs = cast(:specs AS jsonb), updated_at = now()
                WHERE id = :id
                """)
                .param("title", blankToNull(request.title()), Types.VARCHAR)
                .param("body", blankToNull(request.body()), Types.VARCHAR)
                .param("score", valid.average(), Types.NUMERIC)
                .param("cover", sentImages.isEmpty() ? current.get("image_url") : sentImages.getFirst(), Types.VARCHAR)
                .param("images", json.writeValueAsString(sentImages))
                .param("specs", json.writeValueAsString(valid.specs()))
                .param("id", reviewId)
                .update();
        jdbc.sql("DELETE FROM review_scores WHERE review_id = :id").param("id", reviewId).update();
        jdbc.sql("DELETE FROM review_pros_cons WHERE review_id = :id").param("id", reviewId).update();
        writeDetails(reviewId, valid.scores(), request.pros(), request.cons());
        refreshProductScore((UUID) current.get("product_id"));
    }

    @Transactional(readOnly = true)
    public ReviewDraft reviewDraft(UUID userId, UUID reviewId) {
        requireOwner(ContentKind.REVIEW, reviewId, userId);
        Map<String, BigDecimal> scores = new LinkedHashMap<>();
        jdbc.sql("SELECT criterion_id, value FROM review_scores WHERE review_id = :id")
                .param("id", reviewId)
                .query(rs -> {
                    scores.put(String.valueOf(rs.getInt("criterion_id")), rs.getBigDecimal("value"));
                });
        return jdbc.sql("""
                SELECT r.product_id, p.brand, p.name, c.slug, r.title, r.body, r.specs::text AS specs,
                       r.images::text AS images
                FROM reviews r
                JOIN products p ON p.id = r.product_id
                JOIN categories c ON c.id = p.category_id
                WHERE r.id = :id
                """)
                .param("id", reviewId)
                .query((rs, row) -> new ReviewDraft(rs.getObject("product_id", UUID.class),
                        rs.getString("brand") + " " + rs.getString("name"), rs.getString("slug"),
                        rs.getString("title"), rs.getString("body"), scores, parseSpecs(rs.getString("specs")),
                        prosOrCons(reviewId, "pro"), prosOrCons(reviewId, "con"), parseList(rs.getString("images"))))
                .single();
    }

    private List<String> prosOrCons(UUID reviewId, String kind) {
        return jdbc.sql("SELECT text FROM review_pros_cons WHERE review_id = :id AND kind = :kind ORDER BY position")
                .param("id", reviewId).param("kind", kind)
                .query(String.class).list();
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }

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

    private void writeDetails(UUID reviewId, Map<Integer, BigDecimal> scores, List<String> pros, List<String> cons) {
        scores.forEach((criterionId, value) -> jdbc.sql(
                "INSERT INTO review_scores (review_id, criterion_id, value) VALUES (:review, :criterion, :value)")
                .param("review", reviewId).param("criterion", criterionId).param("value", value)
                .update());
        insertProsCons(reviewId, "pro", pros);
        insertProsCons(reviewId, "con", cons);
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

    private void refreshProductScore(UUID productId) {
        jdbc.sql("""
                UPDATE products p
                SET avg_score = s.average, review_count = s.total, updated_at = now()
                FROM (SELECT round(avg(score), 1) AS average, count(*) AS total
                      FROM reviews WHERE product_id = :product AND deleted_at IS NULL) s
                WHERE p.id = :product
                """).param("product", productId).update();
    }

    // Apagar ---------------------------------------------------------------

    @Transactional
    public void delete(ContentKind kind, UUID userId, UUID id) {
        requireOwner(kind, id, userId);
        jdbc.sql("UPDATE " + kind.table() + " SET deleted_at = now() WHERE id = :id").param("id", id).update();
        if (kind == ContentKind.REVIEW) {
            UUID productId = jdbc.sql("SELECT product_id FROM reviews WHERE id = :id")
                    .param("id", id).query(UUID.class).single();
            refreshProductScore(productId);
        }
    }

    /** Dono da publicação. Publicação apagada se comporta como inexistente. */
    public UUID ownerOf(ContentKind kind, UUID id) {
        return jdbc.sql("SELECT user_id FROM " + kind.table() + " WHERE id = :id AND deleted_at IS NULL")
                .param("id", id)
                .query(UUID.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Publicação não encontrada"));
    }

    private void requireOwner(ContentKind kind, UUID id, UUID userId) {
        if (!ownerOf(kind, id).equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Você só pode alterar as suas publicações");
        }
    }

    // Leitura --------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<ProductSummary> products(String category) {
        boolean filtered = category != null && !category.isBlank();
        String sql = """
                SELECT p.id, p.slug, p.name, p.brand, c.name AS category, p.image_url, p.avg_score, p.review_count
                FROM products p JOIN categories c ON c.id = p.category_id
                """ + (filtered ? " WHERE c.slug = :category" : "") + " ORDER BY p.brand, p.name";
        JdbcClient.StatementSpec statement = jdbc.sql(sql);
        if (filtered) {
            statement = statement.param("category", category);
        }
        return statement.query((rs, row) -> new ProductSummary(rs.getObject("id", UUID.class), rs.getString("slug"),
                rs.getString("name"), rs.getString("brand"), rs.getString("category"), rs.getString("image_url"),
                rs.getBigDecimal("avg_score"), rs.getInt("review_count"))).list();
    }

    @Transactional(readOnly = true)
    public ContentResponses.ProductDetail product(String slug) {
        List<ContentResponses.ProductScore> scores = jdbc.sql("""
                SELECT c.label, round(avg(s.value), 1) AS average
                FROM review_scores s
                JOIN criteria c ON c.id = s.criterion_id
                JOIN reviews r ON r.id = s.review_id
                JOIN products p ON p.id = r.product_id
                WHERE p.slug = :slug AND r.deleted_at IS NULL
                GROUP BY c.label, c.position ORDER BY c.position
                """)
                .param("slug", slug)
                .query((rs, row) -> new ContentResponses.ProductScore(rs.getString("label"), rs.getBigDecimal("average")))
                .list();
        // Especificações vêm só do que os reviewers preencheram: o valor mais informado em cada campo da ficha
        List<ContentResponses.ProductSpec> specs = jdbc.sql("""
                SELECT f.label,
                       (SELECT r.specs ->> f.key FROM reviews r
                        WHERE r.product_id = p.id AND r.deleted_at IS NULL
                          AND coalesce(r.specs ->> f.key, '') NOT IN ('', 'Não informado', 'Não se aplica')
                        GROUP BY r.specs ->> f.key
                        ORDER BY count(*) DESC, max(r.created_at) DESC LIMIT 1) AS value
                FROM spec_fields f JOIN products p ON p.category_id = f.category_id
                WHERE p.slug = :slug
                ORDER BY f.position
                """)
                .param("slug", slug)
                .query((rs, row) -> new ContentResponses.ProductSpec(rs.getString("label"), rs.getString("value")))
                .list().stream().filter(spec -> spec.value() != null).toList();
        // O texto "Sobre" não vem de nenhum formulário: não é enviado
        return jdbc.sql("""
                SELECT p.id, p.slug, p.name, p.brand, c.name AS category, p.image_url, p.avg_score, p.review_count
                FROM products p JOIN categories c ON c.id = p.category_id
                WHERE p.slug = :slug
                """)
                .param("slug", slug)
                .query((rs, row) -> new ContentResponses.ProductDetail(rs.getObject("id", UUID.class), rs.getString("slug"),
                        rs.getString("name"), rs.getString("brand"), rs.getString("category"), rs.getString("image_url"),
                        null, specs, rs.getBigDecimal("avg_score"), rs.getInt("review_count"), scores))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Produto não encontrado"));
    }

    @Transactional(readOnly = true)
    public ContentResponses.Hall hall() {
        List<ContentResponses.HallReviewer> reviewers = jdbc.sql("""
                SELECT u.username, u.display_name, u.avatar_url, u.xp, u.level, u.rank_name, count(r.id) AS reviews
                FROM users u JOIN reviews r ON r.user_id = u.id AND r.deleted_at IS NULL
                WHERE u.deleted_at IS NULL
                GROUP BY u.id ORDER BY u.xp DESC, reviews DESC, u.username LIMIT :limit
                """)
                .param("limit", HALL_REVIEWERS)
                .query((rs, row) -> new ContentResponses.HallReviewer(rs.getString("username"), rs.getString("display_name"),
                        rs.getString("avatar_url"), rs.getLong("reviews"), rs.getInt("xp"), rs.getInt("level"),
                        rs.getString("rank_name")))
                .list();
        List<ContentResponses.HallSetup> setups = jdbc.sql("""
                SELECT username, display_name, avatar_url, setup_photos ->> 0 AS photo FROM users
                WHERE deleted_at IS NULL AND jsonb_array_length(setup_photos) > 0
                ORDER BY xp DESC, username LIMIT :limit
                """)
                .param("limit", HALL_SETUPS)
                .query((rs, row) -> new ContentResponses.HallSetup(rs.getString("username"), rs.getString("display_name"),
                        rs.getString("avatar_url"), rs.getString("photo")))
                .list();
        List<ProductSummary> products = jdbc.sql("""
                SELECT p.id, p.slug, p.name, p.brand, c.name AS category, p.image_url, p.avg_score, p.review_count
                FROM products p JOIN categories c ON c.id = p.category_id
                WHERE p.avg_score IS NOT NULL
                ORDER BY p.avg_score DESC, p.review_count DESC, p.brand, p.name LIMIT :limit
                """)
                .param("limit", HALL_PRODUCTS)
                .query((rs, row) -> new ProductSummary(rs.getObject("id", UUID.class), rs.getString("slug"),
                        rs.getString("name"), rs.getString("brand"), rs.getString("category"), rs.getString("image_url"),
                        rs.getBigDecimal("avg_score"), rs.getInt("review_count")))
                .list();
        return new ContentResponses.Hall(reviewers, setups, products);
    }

    /** % e _ digitados são texto comum, não curingas. */
    public static String likePattern(String term) {
        return "%" + term.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    @Transactional(readOnly = true)
    public SearchResults search(String term, UUID viewerId) {
        String clean = term.strip();
        if (clean.length() < MIN_SEARCH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Digite ao menos 2 caracteres para buscar");
        }
        // % e _ digitados são texto comum, não curingas
        String like = likePattern(clean);
        List<ProductSummary> products = jdbc.sql("""
                SELECT p.id, p.slug, p.name, p.brand, c.name AS category, p.image_url, p.avg_score, p.review_count
                FROM products p JOIN categories c ON c.id = p.category_id
                WHERE p.name ILIKE :like OR p.brand ILIKE :like OR p.brand || ' ' || p.name ILIKE :like
                ORDER BY p.review_count DESC, p.name LIMIT :limit
                """)
                .param("like", like).param("limit", SEARCH_LIMIT)
                .query((rs, row) -> new ProductSummary(rs.getObject("id", UUID.class), rs.getString("slug"),
                        rs.getString("name"), rs.getString("brand"), rs.getString("category"),
                        rs.getString("image_url"), rs.getBigDecimal("avg_score"), rs.getInt("review_count")))
                .list();
        List<Author> people = jdbc.sql("""
                SELECT u.username, u.display_name, u.avatar_url, u.rank_name, u.level, u.is_pro,
                       CASE WHEN u.ring_style = 'founder' AND u.founder_number IS NOT NULL THEN 'founder'
                            WHEN EXISTS (SELECT 1 FROM ranks rk WHERE rk.name = u.ring_style AND rk.min_level <= u.level) THEN u.ring_style
                       END AS ring_style,
                       u.founder_number IS NOT NULL AS founder
                FROM users u
                WHERE u.deleted_at IS NULL AND (u.username ILIKE :like OR u.display_name ILIKE :like)
                ORDER BY u.display_name LIMIT :limit
                """)
                .param("like", like).param("limit", SEARCH_LIMIT)
                .query((rs, row) -> author(rs)).list();
        FeedPage publications = feed(new FeedQuery(null, null, null, null, like, SEARCH_LIMIT, null), viewerId);
        return new SearchResults(products, people, publications.items());
    }

    @Transactional(readOnly = true)
    public FeedPage feed(FeedQuery query, UUID viewerId) {
        int size = Math.clamp(query.size(), 1, MAX_PAGE_SIZE);
        List<FeedItem> items = new ArrayList<>(loadReviews(query, size + 1));
        items.addAll(loadPosts(query, size + 1));
        items.sort(Comparator.comparing(FeedItem::createdAt).reversed());

        boolean hasMore = items.size() > size;
        List<FeedItem> page = hasMore ? List.copyOf(items.subList(0, size)) : items;
        return new FeedPage(withViewer(page, viewerId), hasMore ? page.getLast().createdAt() : null);
    }

    private List<FeedItem> withViewer(List<FeedItem> items, UUID viewerId) {
        if (viewerId == null || items.isEmpty()) {
            return items;
        }
        String username = jdbc.sql("SELECT username FROM users WHERE id = :id")
                .param("id", viewerId).query(String.class).single();
        Set<UUID> liked = new HashSet<>();
        Set<UUID> saved = new HashSet<>();
        for (ContentKind kind : ContentKind.values()) {
            String kindName = kind == ContentKind.POST ? "post" : "review";
            List<UUID> ids = items.stream().filter(item -> kindName.equals(item.kind())).map(FeedItem::id).toList();
            if (!ids.isEmpty()) {
                liked.addAll(marked(kind.likesTable(), kind.foreignKey(), viewerId, ids));
                saved.addAll(marked(kind.savedTable(), kind.foreignKey(), viewerId, ids));
            }
        }
        return items.stream()
                .map(item -> item.withViewer(liked.contains(item.id()), saved.contains(item.id()),
                        username.equals(item.author().username())))
                .toList();
    }

    private List<UUID> marked(String table, String foreignKey, UUID viewerId, List<UUID> ids) {
        return jdbc.sql("SELECT " + foreignKey + " FROM " + table + " WHERE user_id = :user AND " + foreignKey + " IN (:ids)")
                .param("user", viewerId).param("ids", ids)
                .query(UUID.class).list();
    }

    private static String filters(ContentKind kind, FeedQuery query) {
        StringBuilder sql = new StringBuilder(" WHERE r.deleted_at IS NULL");
        if (query.category() != null && !query.category().isBlank()) {
            sql.append(" AND c.slug = :category");
        }
        if (query.author() != null && !query.author().isBlank()) {
            sql.append(" AND u.username = :author");
        }
        if (query.product() != null && !query.product().isBlank()) {
            // Reviews do produto e das categorias do mesmo grupo. Posts não têm produto
            sql.append(kind == ContentKind.POST ? " AND false" : """
                     AND c.related_group = (SELECT k.related_group FROM products q
                                            JOIN categories k ON k.id = q.category_id WHERE q.slug = :product)
                    """);
        }
        if (query.before() != null) {
            sql.append(" AND r.created_at < :before");
        }
        if (query.text() != null) {
            sql.append(" AND (r.title ILIKE :text OR r.body ILIKE :text OR u.display_name ILIKE :text");
            sql.append(kind == ContentKind.POST ? ")" : " OR p.brand || ' ' || p.name ILIKE :text)");
        }
        if (query.savedBy() != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM ").append(kind.savedTable()).append(" s WHERE s.")
                    .append(kind.foreignKey()).append(" = r.id AND s.user_id = :savedBy)");
        }
        return sql.toString();
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec statement, FeedQuery query, int limit) {
        JdbcClient.StatementSpec bound = statement.param("limit", limit);
        if (query.category() != null && !query.category().isBlank()) {
            bound = bound.param("category", query.category());
        }
        if (query.author() != null && !query.author().isBlank()) {
            bound = bound.param("author", query.author());
        }
        if (query.product() != null && !query.product().isBlank()) {
            bound = bound.param("product", query.product());
        }
        if (query.before() != null) {
            bound = bound.param("before", Timestamp.from(query.before()));
        }
        if (query.text() != null) {
            bound = bound.param("text", query.text());
        }
        if (query.savedBy() != null) {
            bound = bound.param("savedBy", query.savedBy());
        }
        return bound;
    }

    static Author author(ResultSet rs) throws SQLException {
        return new Author(rs.getString("username"), rs.getString("display_name"), rs.getString("avatar_url"),
                rs.getString("rank_name"), rs.getInt("level"), rs.getBoolean("is_pro"), rs.getString("ring_style"),
                rs.getBoolean("founder"));
    }

    private List<FeedItem> loadPosts(FeedQuery query, int limit) {
        String sql = """
                SELECT r.id, r.title, r.body, r.like_count, r.comment_count, r.created_at, c.name AS category,
                       r.images::text AS images, r.tags::text AS tags,
                       u.username, u.display_name, u.avatar_url, u.rank_name, u.level, u.is_pro,
                       CASE WHEN u.ring_style = 'founder' AND u.founder_number IS NOT NULL THEN 'founder'
                            WHEN EXISTS (SELECT 1 FROM ranks rk WHERE rk.name = u.ring_style AND rk.min_level <= u.level) THEN u.ring_style
                       END AS ring_style,
                       u.founder_number IS NOT NULL AS founder
                FROM posts r
                JOIN users u ON u.id = r.user_id
                LEFT JOIN post_topics c ON c.id = r.topic_id
                """ + filters(ContentKind.POST, query) + " ORDER BY r.created_at DESC LIMIT :limit";
        return bind(jdbc.sql(sql), query, limit)
                .query((rs, row) -> new FeedItem("post", rs.getObject("id", UUID.class), author(rs),
                        rs.getTimestamp("created_at").toInstant(), rs.getString("title"), rs.getString("body"),
                        rs.getString("category"), rs.getInt("like_count"), rs.getInt("comment_count"),
                        null, null, null, List.of(), List.of(), List.of(), List.of(),
                        parseList(rs.getString("tags")), parseList(rs.getString("images")), false, false, false))
                .list();
    }

    private record ReviewRow(FeedItem base, int categoryId, String specsJson) {
    }

    private List<FeedItem> loadReviews(FeedQuery query, int limit) {
        String sql = """
                SELECT r.id, r.title, r.body, r.score, r.cover_url, r.like_count, r.comment_count, r.created_at,
                       r.specs::text AS specs, r.images::text AS images, c.id AS category_id, c.name AS category,
                       p.slug AS product_slug, p.name AS product_name, p.brand, p.image_url,
                       u.username, u.display_name, u.avatar_url, u.rank_name, u.level, u.is_pro,
                       CASE WHEN u.ring_style = 'founder' AND u.founder_number IS NOT NULL THEN 'founder'
                            WHEN EXISTS (SELECT 1 FROM ranks rk WHERE rk.name = u.ring_style AND rk.min_level <= u.level) THEN u.ring_style
                       END AS ring_style,
                       u.founder_number IS NOT NULL AS founder
                FROM reviews r
                JOIN users u ON u.id = r.user_id
                JOIN products p ON p.id = r.product_id
                JOIN categories c ON c.id = p.category_id
                """ + filters(ContentKind.REVIEW, query) + " ORDER BY r.created_at DESC LIMIT :limit";
        List<ReviewRow> rows = bind(jdbc.sql(sql), query, limit)
                .query((rs, row) -> new ReviewRow(new FeedItem("review", rs.getObject("id", UUID.class), author(rs),
                        rs.getTimestamp("created_at").toInstant(), rs.getString("title"), rs.getString("body"),
                        rs.getString("category"), rs.getInt("like_count"), rs.getInt("comment_count"),
                        new ProductRef(rs.getString("product_slug"), rs.getString("product_name"),
                                rs.getString("brand"), rs.getString("category"), rs.getString("image_url")),
                        rs.getBigDecimal("score"), rs.getString("cover_url"),
                        List.of(), List.of(), List.of(), List.of(), List.of(), parseList(rs.getString("images")),
                        false, false, false),
                        rs.getInt("category_id"), rs.getString("specs")))
                .list();
        if (rows.isEmpty()) {
            return List.of();
        }

        List<UUID> ids = rows.stream().map(row -> row.base().id()).toList();
        Map<UUID, List<Score>> scores = new HashMap<>();
        jdbc.sql("""
                SELECT s.review_id, c.label, s.value FROM review_scores s
                JOIN criteria c ON c.id = s.criterion_id
                WHERE s.review_id IN (:ids) ORDER BY c.position
                """).param("ids", ids).query(rs -> {
                    scores.computeIfAbsent(rs.getObject("review_id", UUID.class), id -> new ArrayList<>())
                            .add(new Score(rs.getString("label"), rs.getBigDecimal("value")));
                });
        Map<UUID, List<String>> pros = new HashMap<>();
        Map<UUID, List<String>> cons = new HashMap<>();
        jdbc.sql("SELECT review_id, kind, text FROM review_pros_cons WHERE review_id IN (:ids) ORDER BY position")
                .param("ids", ids).query(rs -> {
                    Map<UUID, List<String>> target = "pro".equals(rs.getString("kind")) ? pros : cons;
                    target.computeIfAbsent(rs.getObject("review_id", UUID.class), id -> new ArrayList<>())
                            .add(rs.getString("text"));
                });

        return rows.stream().map(row -> {
            FeedItem base = row.base();
            return new FeedItem(base.kind(), base.id(), base.author(), base.createdAt(), base.title(), base.body(),
                    base.category(), base.likeCount(), base.commentCount(), base.product(), base.score(),
                    base.coverUrl(), scores.getOrDefault(base.id(), List.of()),
                    labeledSpecs(row.categoryId(), row.specsJson()),
                    pros.getOrDefault(base.id(), List.of()), cons.getOrDefault(base.id(), List.of()),
                    List.of(), base.images(), false, false, false);
        }).toList();
    }

    private Map<String, String> parseSpecs(String specsJson) {
        return json.readValue(specsJson, new TypeReference<Map<String, String>>() {
        });
    }

    private List<Spec> labeledSpecs(int categoryId, String specsJson) {
        Map<String, String> values = parseSpecs(specsJson);
        if (values.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT key, label FROM spec_fields WHERE category_id = :category ORDER BY position")
                .param("category", categoryId)
                .query((rs, row) -> new Spec(rs.getString("label"), values.get(rs.getString("key"))))
                .list().stream().filter(spec -> spec.value() != null).toList();
    }
}
