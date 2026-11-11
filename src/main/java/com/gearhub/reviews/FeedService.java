package com.gearhub.reviews;

import com.gearhub.config.SearchText;
import com.gearhub.reviews.ContentResponses.Author;
import com.gearhub.reviews.ContentResponses.FeedItem;
import com.gearhub.reviews.ContentResponses.FeedPage;
import com.gearhub.reviews.ContentResponses.ProductRef;
import com.gearhub.reviews.ContentResponses.ProductSummary;
import com.gearhub.reviews.ContentResponses.SearchResults;
import com.gearhub.reviews.ContentResponses.Score;
import com.gearhub.reviews.ContentResponses.Spec;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Feed (posts e reviews juntos, do mais novo para o mais antigo), salvos e a busca geral. */
@Service
public class FeedService {

    private static final int MAX_PAGE_SIZE = 50;
    private static final int MIN_SEARCH = 2;
    private static final int SEARCH_LIMIT = 5;

    /**
     * Filtros do feed. Campos nulos não filtram. {@code text} já vem no formato de {@link #likePattern}.
     */
    public record FeedQuery(String category, String author, UUID savedBy, FeedCursor before, String text, int size,
            String product, String game) {
    }

    private final JdbcClient jdbc;
    private final Publications publications;

    public FeedService(JdbcClient jdbc, Publications publications) {
        this.jdbc = jdbc;
        this.publications = publications;
    }

    /** Padrão para {@code gh_unaccent(...) LIKE}: sem acento, e as palavras podem ter texto entre elas. */
    public static String likePattern(String term) {
        return SearchText.pattern(term);
    }

    @Transactional(readOnly = true)
    public FeedPage feed(FeedQuery query, UUID viewerId) {
        int size = Math.clamp(query.size(), 1, MAX_PAGE_SIZE);
        List<FeedItem> items = new ArrayList<>(loadReviews(query, size + 1));
        items.addAll(loadPosts(query, size + 1));
        items.sort(FeedCursor.NEWEST_FIRST);

        boolean hasMore = items.size() > size;
        List<FeedItem> page = hasMore ? List.copyOf(items.subList(0, size)) : items;
        return new FeedPage(withViewer(page, viewerId), hasMore ? FeedCursor.of(page.getLast()).encode() : null);
    }

    @Transactional(readOnly = true)
    public SearchResults search(String term, UUID viewerId) {
        String clean = term.strip();
        if (clean.length() < MIN_SEARCH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Digite ao menos 2 caracteres para buscar");
        }
        // % e _ digitados são texto comum, não curingas
        String like = likePattern(clean);
        List<ProductSummary> products = jdbc.sql("SELECT " + ProductService.SUMMARY_COLUMNS + """

                FROM products p JOIN categories c ON c.id = p.category_id
                WHERE p.game_id IS NULL AND gh_unaccent(concat_ws(' ', p.brand, p.name, c.name)) LIKE :like
                ORDER BY p.review_count DESC, p.name LIMIT :limit
                """)
                .param("like", like).param("limit", SEARCH_LIMIT)
                .query((rs, row) -> ProductService.summary(rs))
                .list();
        // Mesma expressão do índice idx_users_search
        List<Author> people = jdbc.sql("SELECT " + Publications.AUTHOR_COLUMNS + """

                FROM users u
                WHERE u.deleted_at IS NULL AND gh_person_text(u.username, u.display_name) LIKE :like
                ORDER BY u.display_name LIMIT :limit
                """)
                .param("like", like).param("limit", SEARCH_LIMIT)
                .query((rs, row) -> Publications.author(rs)).list();
        FeedPage publications = feed(new FeedQuery(null, null, null, null, like, SEARCH_LIMIT, null, null), viewerId);
        return new SearchResults(products, people, publications.items());
    }

    // Quem está vendo --------------------------------------------------------

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

    // Filtros ----------------------------------------------------------------

    private static String filters(ContentKind kind, FeedQuery query) {
        // Quem pediu para apagar a conta some do feed na hora
        StringBuilder sql = new StringBuilder(" WHERE r.deleted_at IS NULL AND u.deleted_at IS NULL");
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
        if (query.game() != null && !query.game().isBlank()) {
            // Posts sobre o jogo e reviews do jogo
            sql.append(kind == ContentKind.POST ? " AND r.game_id" : " AND p.game_id")
                    .append(" = (SELECT igdb_id FROM catalog_games WHERE slug = :game)");
        }
        if (query.before() != null) {
            // Data e id juntos: no mesmo instante, continua pelo id, na mesma ordem de FeedCursor.NEWEST_FIRST
            sql.append(query.before().id() == null
                    ? " AND r.created_at < :beforeAt"
                    : " AND (r.created_at, r.id) < (:beforeAt, :beforeId)");
        }
        if (query.text() != null) {
            // Todos os campos de uma vez: texto, autor, tópico ou categoria, tags, produto e jogo
            sql.append(kind == ContentKind.POST
                    ? " AND gh_unaccent(concat_ws(' ', r.title, r.body, u.display_name, u.username, c.name, r.tags::text, g.name)) LIKE :text"
                    : " AND gh_unaccent(concat_ws(' ', r.title, r.body, u.display_name, u.username, c.name, p.brand, p.name, g.name)) LIKE :text");
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
        if (query.game() != null && !query.game().isBlank()) {
            bound = bound.param("game", query.game());
        }
        if (query.before() != null) {
            bound = bound.param("beforeAt", Timestamp.from(query.before().createdAt()));
            if (query.before().id() != null) {
                bound = bound.param("beforeId", query.before().id());
            }
        }
        if (query.text() != null) {
            bound = bound.param("text", query.text());
        }
        if (query.savedBy() != null) {
            bound = bound.param("savedBy", query.savedBy());
        }
        return bound;
    }

    // Carregar ---------------------------------------------------------------

    private List<FeedItem> loadPosts(FeedQuery query, int limit) {
        String sql = """
                SELECT r.id, r.title, r.body, r.like_count, r.comment_count, r.created_at, c.name AS category,
                       r.images::text AS images, r.tags::text AS tags,
                       g.slug AS game_slug, g.name AS game_name, g.cover_url AS game_cover,
                """ + Publications.AUTHOR_COLUMNS + """

                FROM posts r
                JOIN users u ON u.id = r.user_id
                LEFT JOIN post_topics c ON c.id = r.topic_id
                LEFT JOIN catalog_games g ON g.igdb_id = r.game_id
                """ + filters(ContentKind.POST, query) + " ORDER BY r.created_at DESC, r.id DESC LIMIT :limit";
        return bind(jdbc.sql(sql), query, limit)
                .query((rs, row) -> new FeedItem("post", rs.getObject("id", UUID.class), Publications.author(rs),
                        rs.getTimestamp("created_at").toInstant(), rs.getString("title"), rs.getString("body"),
                        rs.getString("category"), rs.getInt("like_count"), rs.getInt("comment_count"),
                        null, null, null, List.of(), List.of(), List.of(), List.of(),
                        publications.parseList(rs.getString("tags")), publications.parseList(rs.getString("images")),
                        Publications.gameRef(rs), false, false, false))
                .list();
    }

    private record ReviewRow(FeedItem base, int categoryId, String specsJson) {
    }

    private List<FeedItem> loadReviews(FeedQuery query, int limit) {
        String sql = """
                SELECT r.id, r.title, r.body, r.score, r.cover_url, r.like_count, r.comment_count, r.created_at,
                       r.specs::text AS specs, r.images::text AS images, c.id AS category_id, c.name AS category,
                       p.slug AS product_slug, p.name AS product_name, p.brand, p.image_url,
                       g.slug AS game_slug, g.name AS game_name, g.cover_url AS game_cover,
                """ + Publications.AUTHOR_COLUMNS + """

                FROM reviews r
                JOIN users u ON u.id = r.user_id
                JOIN products p ON p.id = r.product_id
                JOIN categories c ON c.id = p.category_id
                LEFT JOIN catalog_games g ON g.igdb_id = p.game_id
                """ + filters(ContentKind.REVIEW, query) + " ORDER BY r.created_at DESC, r.id DESC LIMIT :limit";
        List<ReviewRow> rows = bind(jdbc.sql(sql), query, limit)
                .query((rs, row) -> new ReviewRow(new FeedItem("review", rs.getObject("id", UUID.class),
                        Publications.author(rs),
                        rs.getTimestamp("created_at").toInstant(), rs.getString("title"), rs.getString("body"),
                        rs.getString("category"), rs.getInt("like_count"), rs.getInt("comment_count"),
                        new ProductRef(rs.getString("product_slug"), rs.getString("product_name"),
                                rs.getString("brand"), rs.getString("category"), rs.getString("image_url")),
                        rs.getBigDecimal("score"), rs.getString("cover_url"),
                        List.of(), List.of(), List.of(), List.of(), List.of(),
                        publications.parseList(rs.getString("images")), Publications.gameRef(rs), false, false, false),
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
        // Rótulos da ficha de todas as categorias da página numa consulta só, e não uma por review
        List<Integer> categoryIds = rows.stream().map(ReviewRow::categoryId).distinct().toList();
        Map<Integer, List<String[]>> fields = new HashMap<>();
        jdbc.sql("SELECT category_id, key, label FROM spec_fields WHERE category_id IN (:ids) ORDER BY position")
                .param("ids", categoryIds).query(rs -> {
                    fields.computeIfAbsent(rs.getInt("category_id"), id -> new ArrayList<>())
                            .add(new String[] {rs.getString("key"), rs.getString("label")});
                });

        return rows.stream().map(row -> {
            FeedItem base = row.base();
            return new FeedItem(base.kind(), base.id(), base.author(), base.createdAt(), base.title(), base.body(),
                    base.category(), base.likeCount(), base.commentCount(), base.product(), base.score(),
                    base.coverUrl(), scores.getOrDefault(base.id(), List.of()),
                    labeledSpecs(fields.getOrDefault(row.categoryId(), List.of()), row.specsJson()),
                    pros.getOrDefault(base.id(), List.of()), cons.getOrDefault(base.id(), List.of()),
                    List.of(), base.images(), base.game(), false, false, false);
        }).toList();
    }

    /** {@code fields}: pares chave e rótulo da ficha, na ordem de exibição. */
    private List<Spec> labeledSpecs(List<String[]> fields, String specsJson) {
        Map<String, String> values = publications.parseSpecs(specsJson);
        if (values.isEmpty()) {
            return List.of();
        }
        return fields.stream()
                .map(field -> new Spec(field[1], values.get(field[0])))
                .filter(spec -> spec.value() != null)
                .toList();
    }
}
