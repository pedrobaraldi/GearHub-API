package com.gearhub.reviews;

import com.gearhub.admin.AuditService;
import com.gearhub.catalog.CatalogResponses;
import com.gearhub.catalog.CatalogService;
import com.gearhub.config.FieldValidationException;
import com.gearhub.reviews.ContentResponses.Author;
import com.gearhub.reviews.ContentResponses.GameRef;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * O que posts, reviews, feed e comentários compartilham: quem é o dono, o histórico para a auditoria,
 * o jogo do catálogo e a leitura das colunas que se repetem.
 */
@Component
public class Publications {

    /** Colunas do autor lidas por {@link #author}. A consulta precisa juntar o autor como {@code u}. */
    public static final String AUTHOR_COLUMNS = """
            u.username, u.display_name, u.avatar_url, u.rank_name, u.level, u.is_pro,
            gh_ring_style(u.ring_style, u.founder_number, u.level) AS ring_style,
            u.founder_number IS NOT NULL AS founder""";

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final ContentImageService images;
    private final CatalogService catalog;
    private final AuditService audit;

    public Publications(JdbcClient jdbc, ObjectMapper json, ContentImageService images, CatalogService catalog,
            AuditService audit) {
        this.jdbc = jdbc;
        this.json = json;
        this.images = images;
        this.catalog = catalog;
        this.audit = audit;
    }

    // Dono ------------------------------------------------------------------

    /** Dono da publicação. Publicação apagada se comporta como inexistente. */
    public UUID ownerOf(ContentKind kind, UUID id) {
        return jdbc.sql("SELECT user_id FROM " + kind.table() + " WHERE id = :id AND deleted_at IS NULL")
                .param("id", id)
                .query(UUID.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Publicação não encontrada"));
    }

    void requireOwner(ContentKind kind, UUID id, UUID userId) {
        if (!ownerOf(kind, id).equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Você só pode alterar as suas publicações");
        }
    }

    /** Exclusão lógica: some do site, mas continua no banco e na auditoria. */
    void markDeleted(ContentKind kind, UUID userId, UUID id) {
        requireOwner(kind, id, userId);
        jdbc.sql("UPDATE " + kind.table() + " SET deleted_at = now() WHERE id = :id").param("id", id).update();
        audit.record(userId, kind == ContentKind.POST ? "post.delete" : "review.delete",
                kind == ContentKind.POST ? "post" : "review", id, Map.of());
    }

    /** Como a publicação está agora, para a auditoria guardar o antes e o depois de cada edição. */
    Map<String, Object> snapshot(ContentKind kind, UUID id) {
        String sql = kind == ContentKind.POST ? """
                SELECT jsonb_build_object('texto', p.body, 'topico', t.slug, 'tags', p.tags, 'imagens', p.images,
                       'jogo', g.slug)::text
                FROM posts p LEFT JOIN post_topics t ON t.id = p.topic_id LEFT JOIN catalog_games g ON g.igdb_id = p.game_id
                WHERE p.id = :id
                """ : """
                SELECT jsonb_build_object('titulo', r.title, 'texto', r.body, 'nota', r.score, 'imagens', r.images,
                       'fichaTecnica', r.specs,
                       'notas', (SELECT jsonb_object_agg(c.label, s.value) FROM review_scores s
                                 JOIN criteria c ON c.id = s.criterion_id WHERE s.review_id = r.id),
                       'pros', (SELECT jsonb_agg(text ORDER BY position) FROM review_pros_cons WHERE review_id = r.id AND kind = 'pro'),
                       'contras', (SELECT jsonb_agg(text ORDER BY position) FROM review_pros_cons WHERE review_id = r.id AND kind = 'con'))::text
                FROM reviews r WHERE r.id = :id
                """;
        return json.readValue(jdbc.sql(sql).param("id", id).query(String.class).single(),
                new TypeReference<Map<String, Object>>() {
                });
    }

    // Jogo e imagens ---------------------------------------------------------

    /** Confere o jogo no catálogo (e guarda a ficha): nome e capa nunca vêm do navegador. */
    CatalogResponses.GamePage catalogGame(String slug) {
        try {
            return catalog.game(slug.strip()).game();
        } catch (ResponseStatusException e) {
            throw new FieldValidationException(Map.of("game", e.getStatusCode().value() == 404
                    ? "Jogo não encontrado. Escolha outro."
                    : "Não foi possível conferir o jogo agora. Tente de novo em instantes."));
        }
    }

    /** {@code current} são as imagens já salvas, que podem ser mantidas como estão. */
    List<String> validImages(List<String> sent, List<String> current) {
        if (sent == null) {
            return List.of();
        }
        List<String> valid = sent.stream().filter(url -> url != null && !url.isBlank()).distinct().toList();
        if (!valid.stream().allMatch(url -> current.contains(url) || images.isOwnFile(url))) {
            throw new FieldValidationException(Map.of("images", "Imagem inválida. Envie o arquivo de novo."));
        }
        return valid;
    }

    // Leitura de colunas -------------------------------------------------------

    public static Author author(ResultSet rs) throws SQLException {
        return new Author(rs.getString("username"), rs.getString("display_name"), rs.getString("avatar_url"),
                rs.getString("rank_name"), rs.getInt("level"), rs.getBoolean("is_pro"), rs.getString("ring_style"),
                rs.getBoolean("founder"));
    }

    static GameRef gameRef(ResultSet rs) throws SQLException {
        String slug = rs.getString("game_slug");
        return slug == null ? null : new GameRef(slug, rs.getString("game_name"), rs.getString("game_cover"));
    }

    List<String> parseList(String listJson) {
        return json.readValue(listJson, new TypeReference<List<String>>() {
        });
    }

    Map<String, String> parseSpecs(String specsJson) {
        return json.readValue(specsJson, new TypeReference<Map<String, String>>() {
        });
    }

    String toJson(Object value) {
        return json.writeValueAsString(value);
    }
}
