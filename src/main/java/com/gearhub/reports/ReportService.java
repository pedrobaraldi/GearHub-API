package com.gearhub.reports;

import com.gearhub.admin.AuditService;
import com.gearhub.auth.RateLimiter;
import com.gearhub.config.FieldValidationException;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.sql.Types;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Denúncias de posts, reviews, comentários e perfis. Por enquanto só registram e listam para o admin:
 * nenhuma ação automática acontece. As regras de moderação (esconder, avisar, banir) ficam para depois.
 */
@Service
public class ReportService {

    private static final int REPORTS_PER_MINUTE = 10;
    private static final List<String> STATUSES = List.of("pending", "dismissed", "resolved");

    /** Motivos, na ordem em que aparecem no site. A chave é o que fica no banco. */
    private static final Map<String, String> REASONS = new LinkedHashMap<>();

    static {
        REASONS.put("spam", "Spam ou propaganda");
        REASONS.put("harassment", "Assédio ou ofensa");
        REASONS.put("hate", "Discurso de ódio");
        REASONS.put("inappropriate", "Conteúdo impróprio (nudez, violência)");
        REASONS.put("scam", "Golpe ou fraude");
        REASONS.put("misinformation", "Informação falsa ou enganosa");
        REASONS.put("impersonation", "Perfil falso ou se passando por outra pessoa");
        REASONS.put("other", "Outro motivo");
    }

    // Onde está o dono de cada tipo de conteúdo. Os nomes são fixos: nunca vêm de quem chama a API
    private static final Map<String, String> OWNER_SQL = Map.of(
            "post", "SELECT user_id FROM posts WHERE id = :id AND deleted_at IS NULL",
            "review", "SELECT user_id FROM reviews WHERE id = :id AND deleted_at IS NULL",
            "comment", "SELECT user_id FROM comments WHERE id = :id AND deleted_at IS NULL",
            "user", "SELECT id FROM users WHERE id = :id AND deleted_at IS NULL");

    public record Reason(@Schema(example = "spam") String key, @Schema(example = "Spam ou propaganda") String label) {
    }

    public record CreateReport(
            @Schema(allowableValues = {"post", "review", "comment", "user"}) @NotBlank(message = "Escolha o que denunciar") String kind,
            @Schema(description = "Id do conteúdo (post, review ou comentário)") UUID id,
            @Schema(description = "Nome de usuário, para denunciar um perfil (kind = user)", example = "pedrolm") String username,
            @Schema(example = "spam") @NotBlank(message = "Escolha um motivo") String reason,
            @Size(max = 500, message = "Use no máximo 500 caracteres") String details) {
    }

    @Schema(description = "Conteúdo ou perfil denunciado, com as denúncias agrupadas")
    public record ReportedItem(String kind, UUID targetId,
            @Schema(description = "Trecho do conteúdo (ou nome do perfil); vazio se já foi apagado") String snippet,
            @Schema(description = "Dono do conteúdo, ou o próprio perfil") String authorUsername, String authorName,
            @Schema(description = "Onde ver no site") String link,
            int reports,
            @Schema(description = "Quantas denúncias por motivo", example = "{\"spam\": 2}") Map<String, Integer> reasons,
            @Schema(description = "Detalhes escritos por quem denunciou (até 5)") List<String> details,
            Instant firstAt, Instant lastAt,
            @Schema(allowableValues = {"pending", "dismissed", "resolved"}) String status) {
    }

    private final JdbcClient jdbc;
    private final RateLimiter rateLimiter;
    private final ObjectMapper json;
    private final AuditService audit;

    public ReportService(JdbcClient jdbc, RateLimiter rateLimiter, ObjectMapper json, AuditService audit) {
        this.jdbc = jdbc;
        this.rateLimiter = rateLimiter;
        this.json = json;
        this.audit = audit;
    }

    public List<Reason> reasons() {
        return REASONS.entrySet().stream().map(entry -> new Reason(entry.getKey(), entry.getValue())).toList();
    }

    @Transactional
    public void report(UUID reporterId, CreateReport request) {
        rateLimiter.check("report:" + reporterId, REPORTS_PER_MINUTE);
        String ownerSql = OWNER_SQL.get(request.kind());
        // Perfil é denunciado pelo nome de usuário, que é o que o site conhece
        UUID targetId = "user".equals(request.kind()) && request.id() == null && request.username() != null
                ? jdbc.sql("SELECT id FROM users WHERE username = :username AND deleted_at IS NULL")
                        .param("username", request.username().strip()).query(UUID.class).optional()
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuário não encontrado"))
                : request.id();
        if (ownerSql == null || targetId == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Publicação não encontrada");
        }
        if (!REASONS.containsKey(request.reason())) {
            throw new FieldValidationException(Map.of("reason", "Escolha um motivo"));
        }
        UUID owner = jdbc.sql(ownerSql).param("id", targetId).query(UUID.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "user".equals(request.kind()) ? "Usuário não encontrado" : "Publicação não encontrada"));
        if (owner.equals(reporterId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Você não pode denunciar a si mesmo");
        }
        String details = request.details() == null || request.details().isBlank() ? null : request.details().strip();
        try {
            jdbc.sql("""
                    INSERT INTO reports (reporter_id, target_kind, target_id, reason, details)
                    VALUES (:reporter, :kind, :target, :reason, :details)
                    """)
                    .param("reporter", reporterId).param("kind", request.kind()).param("target", targetId)
                    .param("reason", request.reason()).param("details", details, Types.VARCHAR)
                    .update();
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Você já denunciou isso. A equipe vai analisar.");
        }
        audit.record(reporterId, "report.create", request.kind(), targetId, Map.of("reason", request.reason()));
    }

    // ---------- Admin ----------

    /** Denunciados, os mais denunciados primeiro. {@code status}: pending (padrão), dismissed ou resolved. */
    @Transactional(readOnly = true)
    public List<ReportedItem> list(String status, String username) {
        String wanted = status != null && STATUSES.contains(status) ? status : "pending";
        boolean byUser = username != null && !username.isBlank();
        return jdbc.sql("""
                WITH grouped AS (
                    SELECT target_kind, target_id, count(*) AS total, min(created_at) AS first_at, max(created_at) AS last_at
                    FROM reports WHERE status = :status GROUP BY target_kind, target_id
                ), owners AS (
                    SELECT g.*, CASE g.target_kind
                               WHEN 'post' THEN (SELECT user_id FROM posts WHERE id = g.target_id)
                               WHEN 'review' THEN (SELECT user_id FROM reviews WHERE id = g.target_id)
                               WHEN 'comment' THEN (SELECT user_id FROM comments WHERE id = g.target_id)
                               WHEN 'user' THEN g.target_id
                           END AS owner_id
                    FROM grouped g
                )
                SELECT o.target_kind, o.target_id, o.total, o.first_at, o.last_at, a.username, a.display_name,
                       (SELECT jsonb_object_agg(x.reason, x.total)::text FROM (
                            SELECT reason, count(*) AS total FROM reports
                            WHERE target_kind = o.target_kind AND target_id = o.target_id AND status = :status GROUP BY reason) x) AS reasons,
                       (SELECT jsonb_agg(d.details)::text FROM (
                            SELECT details FROM reports
                            WHERE target_kind = o.target_kind AND target_id = o.target_id AND status = :status AND details IS NOT NULL
                            ORDER BY created_at DESC LIMIT 5) d) AS details,
                       CASE o.target_kind
                           WHEN 'post' THEN (SELECT body FROM posts WHERE id = o.target_id AND deleted_at IS NULL)
                           WHEN 'review' THEN (SELECT coalesce(title, body) FROM reviews WHERE id = o.target_id AND deleted_at IS NULL)
                           WHEN 'comment' THEN (SELECT body FROM comments WHERE id = o.target_id AND deleted_at IS NULL)
                           WHEN 'user' THEN a.display_name
                       END AS snippet,
                       (SELECT coalesce('/games/' || g.slug || '#comunidade', '/product/' || p.slug || '?review=' || rv.id)
                          FROM reviews rv JOIN products p ON p.id = rv.product_id
                          LEFT JOIN catalog_games g ON g.igdb_id = p.game_id
                         WHERE rv.id = CASE WHEN o.target_kind = 'review' THEN o.target_id
                                            WHEN o.target_kind = 'comment' THEN (SELECT review_id FROM comments WHERE id = o.target_id)
                                       END) AS review_link
                FROM owners o
                LEFT JOIN users a ON a.id = o.owner_id
                WHERE (NOT :byUser OR a.username = :username)
                ORDER BY o.total DESC, o.last_at DESC
                LIMIT 200
                """)
                .param("status", wanted).param("byUser", byUser).param("username", byUser ? username : "")
                .query((rs, row) -> {
                    String owner = rs.getString("username");
                    String reviewLink = rs.getString("review_link");
                    return new ReportedItem(rs.getString("target_kind"), rs.getObject("target_id", UUID.class),
                            rs.getString("snippet"), owner, rs.getString("display_name"),
                            reviewLink != null ? reviewLink : owner != null ? "/profile/" + owner : null,
                            rs.getInt("total"), read(rs.getString("reasons"), new TypeReference<Map<String, Integer>>() {
                            }, Map.of()),
                            read(rs.getString("details"), new TypeReference<List<String>>() {
                            }, List.of()),
                            rs.getTimestamp("first_at").toInstant(), rs.getTimestamp("last_at").toInstant(), wanted);
                })
                .list();
    }

    /** Fecha as denúncias pendentes de um alvo: dispensadas (nada errado) ou resolvidas (já tratado). */
    @Transactional
    public int review(UUID adminId, String kind, UUID targetId, String status, String note) {
        if (status == null || !List.of("dismissed", "resolved").contains(status)) {
            throw new FieldValidationException(Map.of("status", "Use dismissed ou resolved"));
        }
        String cleanNote = note == null || note.isBlank() ? null : note.strip();
        int updated = jdbc.sql("""
                UPDATE reports SET status = :status, reviewed_by = :admin, reviewed_at = now(), review_note = :note
                WHERE target_kind = :kind AND target_id = :target AND status = 'pending'
                """)
                .param("status", status).param("admin", adminId).param("kind", kind).param("target", targetId)
                .param("note", cleanNote, Types.VARCHAR)
                .update();
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Não há denúncias pendentes para isso");
        }
        audit.record(adminId, "admin.report_review", kind, targetId,
                cleanNote == null ? Map.of("status", status, "reports", updated)
                        : Map.of("status", status, "reports", updated, "note", cleanNote));
        return updated;
    }

    /** Denúncias pendentes contra o conteúdo ou o perfil de alguém. */
    @Transactional(readOnly = true)
    public int pendingAgainst(UUID userId) {
        return jdbc.sql("""
                SELECT count(*) FROM reports r WHERE r.status = 'pending' AND (
                    (r.target_kind = 'user' AND r.target_id = :user)
                    OR (r.target_kind = 'post' AND r.target_id IN (SELECT id FROM posts WHERE user_id = :user))
                    OR (r.target_kind = 'review' AND r.target_id IN (SELECT id FROM reviews WHERE user_id = :user))
                    OR (r.target_kind = 'comment' AND r.target_id IN (SELECT id FROM comments WHERE user_id = :user)))
                """).param("user", userId).query(Integer.class).single();
    }

    private <T> T read(String value, TypeReference<T> type, T empty) {
        return value == null ? empty : json.readValue(value, type);
    }
}
