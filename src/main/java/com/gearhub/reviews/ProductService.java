package com.gearhub.reviews;

import com.gearhub.reviews.ContentResponses.ProductSummary;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Produtos avaliados, a nota de cada um e o Hall da Fama. */
@Service
public class ProductService {

    private static final int HALL_REVIEWERS = 20;
    private static final int HALL_SETUPS = 6;
    private static final int HALL_PRODUCTS = 4;

    /** Colunas lidas por {@link #summary}: produto como {@code p} e categoria como {@code c}. */
    static final String SUMMARY_COLUMNS = "p.id, p.slug, p.name, p.brand, c.name AS category, p.image_url, p.avg_score, p.review_count";

    private final JdbcClient jdbc;

    public ProductService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    static ProductSummary summary(ResultSet rs) throws SQLException {
        return new ProductSummary(rs.getObject("id", UUID.class), rs.getString("slug"), rs.getString("name"),
                rs.getString("brand"), rs.getString("category"), rs.getString("image_url"),
                rs.getBigDecimal("avg_score"), rs.getInt("review_count"));
    }

    @Transactional(readOnly = true)
    public List<ProductSummary> list(String category) {
        boolean filtered = category != null && !category.isBlank();
        String sql = "SELECT " + SUMMARY_COLUMNS + """

                FROM products p JOIN categories c ON c.id = p.category_id
                WHERE p.game_id IS NULL""" + (filtered ? " AND c.slug = :category" : "") + " ORDER BY p.brand, p.name";
        JdbcClient.StatementSpec statement = jdbc.sql(sql);
        if (filtered) {
            statement = statement.param("category", category);
        }
        return statement.query((rs, row) -> summary(rs)).list();
    }

    @Transactional(readOnly = true)
    public ContentResponses.ProductDetail detail(String slug) {
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
        return jdbc.sql("SELECT " + SUMMARY_COLUMNS + """

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
        List<ProductSummary> products = jdbc.sql("SELECT " + SUMMARY_COLUMNS + """

                FROM products p JOIN categories c ON c.id = p.category_id
                WHERE p.avg_score IS NOT NULL AND p.game_id IS NULL
                ORDER BY p.avg_score DESC, p.review_count DESC, p.brand, p.name LIMIT :limit
                """)
                .param("limit", HALL_PRODUCTS)
                .query((rs, row) -> summary(rs))
                .list();
        return new ContentResponses.Hall(reviewers, setups, products);
    }

    /** Nota do produto: média das reviews ativas, sem as de quem pediu para apagar a conta. */
    @Transactional
    public void refreshScore(UUID productId) {
        jdbc.sql("""
                UPDATE products p
                SET avg_score = s.average, review_count = s.total, updated_at = now()
                FROM (SELECT round(avg(r.score), 1) AS average, count(*) AS total
                      FROM reviews r JOIN users u ON u.id = r.user_id
                      WHERE r.product_id = :product AND r.deleted_at IS NULL AND u.deleted_at IS NULL) s
                WHERE p.id = :product
                """).param("product", productId).update();
    }

    /** Conta apagada ou restaurada: recalcula a nota de todo produto que a pessoa avaliou. */
    @Transactional
    public void refreshScoresOfAuthor(UUID userId) {
        jdbc.sql("SELECT DISTINCT product_id FROM reviews WHERE user_id = :user").param("user", userId)
                .query(UUID.class).list().forEach(this::refreshScore);
    }
}
