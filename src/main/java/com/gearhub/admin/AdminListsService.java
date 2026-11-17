package com.gearhub.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Listas do painel de admin: usuários, auditoria de todo o site, lojas e produtos do marketplace. */
@Service
public class AdminListsService {

    private static final int PAGE = 50;
    private static final int MAX_AUDIT = 100;

    @Schema(description = "Linha da lista de usuários")
    public record UserRow(String username, String displayName, String role, int level, boolean twoFactorEnabled,
            boolean emailVerified, Instant createdAt,
            @Schema(description = "Exclusão pedida em") Instant deletedAt,
            @Schema(description = "Denúncias pendentes contra o perfil ou o que ele publicou") int pendingReportsAgainst) {
    }

    public record UserPage(List<UserRow> items, @Schema(description = "Próxima página; nulo no fim") Integer nextPage) {
    }

    @Schema(description = "Evento da auditoria, com quem fez")
    public record AuditEvent(long id, @Schema(description = "@ de quem fez; nulo para o sistema") String actorUsername,
            String action, String targetKind, UUID targetId, Map<String, Object> data, Instant createdAt) {
    }

    public record AuditPage(List<AuditEvent> items, @Schema(description = "Passe em before para a próxima página") Instant nextCursor) {
    }

    @Schema(description = "Loja para o admin, inclusive suspensa")
    public record StoreRow(String slug, String name, String ownerUsername, String segment, String status, String suspendedNote,
            int activeProducts, int removedProducts, int clicksLast30Days, Instant createdAt) {
    }

    @Schema(description = "Produto para o admin, inclusive tirado do ar")
    public record ProductRow(UUID id, String storeSlug, String storeName, String name, BigDecimal price, String status,
            String purchaseUrl, String firstImage, Instant removedAt, String removedNote, Instant createdAt) {
    }

    @Schema(description = "Publicação, review ou comentário da pessoa, com o texto completo (inclusive o que ela apagou)")
    public record ContentItem(@Schema(allowableValues = {"post", "review", "comment"}) String kind, UUID id, String title,
            String text, @Schema(description = "Comentário: em post ou review") String parentKind, UUID parentId,
            int images, Instant createdAt, Instant updatedAt,
            @Schema(description = "Quando foi apagado (pela pessoa ou pela moderação); nulo se está no ar") Instant deletedAt) {
    }

    public record ContentPage(List<ContentItem> items, @Schema(description = "Passe em before para a próxima página") Instant nextCursor) {
    }

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public AdminListsService(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** O e-mail fica criptografado: a busca é pelo @ ou pelo nome. */
    @Transactional(readOnly = true)
    public UserPage users(String query, String filter, int page) {
        int current = Math.max(0, page);
        String q = query == null ? "" : query.strip().replaceFirst("^@", "").toLowerCase(Locale.ROOT);
        String like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        String f = filter == null ? "" : filter;
        List<UserRow> rows = jdbc.sql("""
                SELECT u.username, u.display_name, u.role, u.level, u.two_factor_enabled_at IS NOT NULL AS two_factor,
                       u.email_verified_at IS NOT NULL AS verified, u.created_at, u.deleted_at,
                       (SELECT count(*) FROM reports r WHERE r.status = 'pending' AND (
                            (r.target_kind = 'user' AND r.target_id = u.id)
                         OR (r.target_kind = 'post' AND r.target_id IN (SELECT id FROM posts WHERE user_id = u.id))
                         OR (r.target_kind = 'review' AND r.target_id IN (SELECT id FROM reviews WHERE user_id = u.id))
                         OR (r.target_kind = 'comment' AND r.target_id IN (SELECT id FROM comments WHERE user_id = u.id)))) AS reports
                FROM users u
                WHERE (:q = '' OR lower(u.username) LIKE :like OR lower(u.display_name) LIKE :like)
                  AND (:filter = '' OR (:filter = 'admin' AND u.role = 'admin')
                       OR (:filter = 'deleting' AND u.deleted_at IS NOT NULL)
                       OR (:filter = 'no2fa' AND u.two_factor_enabled_at IS NULL AND u.deleted_at IS NULL))
                ORDER BY u.created_at DESC, u.id
                LIMIT :limit OFFSET :offset
                """)
                .param("q", q).param("like", like).param("filter", f)
                .param("limit", PAGE + 1).param("offset", current * PAGE)
                .query((rs, row) -> new UserRow(rs.getString("username"), rs.getString("display_name"), rs.getString("role"),
                        rs.getInt("level"), rs.getBoolean("two_factor"), rs.getBoolean("verified"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("deleted_at") == null ? null : rs.getTimestamp("deleted_at").toInstant(),
                        rs.getInt("reports")))
                .list();
        boolean more = rows.size() > PAGE;
        return new UserPage(more ? rows.subList(0, PAGE) : rows, more ? current + 1 : null);
    }

    /** Toda a auditoria, da mais nova para a mais antiga. Filtra por quem fez e pelo começo da ação ("admin.", "post."). */
    @Transactional(readOnly = true)
    public AuditPage audit(String username, String action, Instant before, int size) {
        int limit = Math.max(1, Math.min(size, MAX_AUDIT));
        String user = username == null ? "" : username.strip().replaceFirst("^@", "").toLowerCase(Locale.ROOT);
        String prefix = action == null ? "" : action.strip().toLowerCase(Locale.ROOT);
        List<AuditEvent> items = jdbc.sql("""
                SELECT a.id, u.username, a.action, a.target_kind, a.target_id, a.data::text AS data, a.created_at
                FROM audit_events a LEFT JOIN users u ON u.id = a.actor_id
                WHERE (:user = '' OR lower(u.username) = :user)
                  AND (:prefix = '' OR a.action LIKE :prefixLike)
                  AND (CAST(:before AS timestamptz) IS NULL OR a.created_at < :before)
                ORDER BY a.created_at DESC, a.id DESC LIMIT :limit
                """)
                .param("user", user).param("prefix", prefix)
                .param("prefixLike", prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%")
                .param("before", before == null ? null : Timestamp.from(before), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("limit", limit)
                .query((rs, row) -> new AuditEvent(rs.getLong("id"), rs.getString("username"), rs.getString("action"),
                        rs.getString("target_kind"), rs.getObject("target_id", UUID.class),
                        json.readValue(rs.getString("data"), new TypeReference<Map<String, Object>>() {
                        }),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
        Instant next = items.size() == limit ? items.get(items.size() - 1).createdAt() : null;
        return new AuditPage(items, next);
    }

    /** Tudo o que a pessoa escreveu, do mais novo para o mais antigo; nada some daqui, porque a exclusão é lógica. */
    @Transactional(readOnly = true)
    public ContentPage userContent(String username, String kind, Instant before, int size) {
        int limit = Math.max(1, Math.min(size, MAX_AUDIT));
        UUID userId = jdbc.sql("SELECT id FROM users WHERE lower(username) = lower(:username)")
                .param("username", username.strip().replaceFirst("^@", "")).query(UUID.class).optional()
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Usuário não encontrado"));
        String k = kind == null ? "" : kind;
        List<ContentItem> items = jdbc.sql("""
                SELECT * FROM (
                    SELECT 'post' AS kind, id, title, body AS text, NULL AS parent_kind, NULL::uuid AS parent_id,
                           jsonb_array_length(images) AS images, created_at, updated_at, deleted_at
                    FROM posts WHERE user_id = :user AND (:kind = '' OR :kind = 'post')
                    UNION ALL
                    SELECT 'review', id, title, body, NULL, NULL, jsonb_array_length(images), created_at, updated_at, deleted_at
                    FROM reviews WHERE user_id = :user AND (:kind = '' OR :kind = 'review')
                    UNION ALL
                    SELECT 'comment', id, NULL, body, CASE WHEN post_id IS NOT NULL THEN 'post' ELSE 'review' END,
                           coalesce(post_id, review_id), 0, created_at, NULL, deleted_at
                    FROM comments WHERE user_id = :user AND (:kind = '' OR :kind = 'comment')
                ) content
                WHERE CAST(:before AS timestamptz) IS NULL OR created_at < :before
                ORDER BY created_at DESC, id DESC LIMIT :limit
                """)
                .param("user", userId).param("kind", k)
                .param("before", before == null ? null : Timestamp.from(before), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("limit", limit)
                .query((rs, row) -> new ContentItem(rs.getString("kind"), rs.getObject("id", UUID.class), rs.getString("title"),
                        rs.getString("text"), rs.getString("parent_kind"), rs.getObject("parent_id", UUID.class), rs.getInt("images"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toInstant(),
                        rs.getTimestamp("deleted_at") == null ? null : rs.getTimestamp("deleted_at").toInstant()))
                .list();
        Instant next = items.size() == limit ? items.get(items.size() - 1).createdAt() : null;
        return new ContentPage(items, next);
    }

    @Transactional(readOnly = true)
    public List<StoreRow> stores(String status) {
        String s = status == null ? "" : status;
        return jdbc.sql("""
                SELECT s.slug, s.name, u.username, s.segment, s.status, s.suspended_note, s.created_at,
                       (SELECT count(*) FROM store_products p WHERE p.store_id = s.id AND p.deleted_at IS NULL
                          AND p.removed_at IS NULL AND p.status = 'active') AS active_products,
                       (SELECT count(*) FROM store_products p WHERE p.store_id = s.id AND p.deleted_at IS NULL
                          AND p.removed_at IS NOT NULL) AS removed_products,
                       (SELECT count(*) FROM click_events c WHERE c.store_id = s.id AND c.created_at > now() - interval '30 days') AS clicks
                FROM partner_stores s JOIN users u ON u.id = s.owner_id
                WHERE s.deleted_at IS NULL AND (:status = '' OR s.status = :status)
                ORDER BY s.created_at DESC
                """)
                .param("status", s)
                .query((rs, row) -> new StoreRow(rs.getString("slug"), rs.getString("name"), rs.getString("username"),
                        rs.getString("segment"), rs.getString("status"), rs.getString("suspended_note"),
                        rs.getInt("active_products"), rs.getInt("removed_products"), rs.getInt("clicks"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    @Transactional(readOnly = true)
    public List<ProductRow> products(String storeSlug, boolean removedOnly) {
        String slug = storeSlug == null ? "" : storeSlug.strip();
        return jdbc.sql("""
                SELECT p.id, s.slug, s.name AS store_name, p.name, p.price, p.status, p.purchase_url,
                       p.images ->> 0 AS first_image, p.removed_at, p.removed_note, p.created_at
                FROM store_products p JOIN partner_stores s ON s.id = p.store_id
                WHERE p.deleted_at IS NULL AND s.deleted_at IS NULL
                  AND (:slug = '' OR s.slug = :slug) AND (NOT :removed OR p.removed_at IS NOT NULL)
                ORDER BY p.created_at DESC
                LIMIT 200
                """)
                .param("slug", slug).param("removed", removedOnly)
                .query((rs, row) -> new ProductRow(rs.getObject("id", UUID.class), rs.getString("slug"),
                        rs.getString("store_name"), rs.getString("name"), rs.getBigDecimal("price"), rs.getString("status"),
                        rs.getString("purchase_url"), rs.getString("first_image"),
                        rs.getTimestamp("removed_at") == null ? null : rs.getTimestamp("removed_at").toInstant(),
                        rs.getString("removed_note"), rs.getTimestamp("created_at").toInstant()))
                .list();
    }
}
