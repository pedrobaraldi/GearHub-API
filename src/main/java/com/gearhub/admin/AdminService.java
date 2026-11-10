package com.gearhub.admin;

import com.gearhub.crypto.CryptoService;
import com.gearhub.reports.ReportService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Dados do futuro painel de admin: visão geral do site e a ficha de moderação de um perfil. */
@Service
public class AdminService {

    @Schema(description = "Números do painel")
    public record Overview(int pendingReports, int reportedItems, int users, int newUsersLast7Days,
            int postsLast7Days, int reviewsLast7Days, int commentsLast7Days,
            @Schema(description = "Contas no prazo de 30 dias para desistir da exclusão") int accountsPendingDeletion) {
    }

    @Schema(description = "Ficha de moderação de um perfil")
    public record UserFile(UUID id, String username, String displayName, String email, String role, int level,
            boolean twoFactorEnabled, Instant createdAt,
            @Schema(description = "Exclusão pedida em") Instant deletedAt,
            int posts, int reviews, int comments,
            @Schema(description = "Denúncias pendentes contra o perfil ou o que ele publicou") int pendingReportsAgainst,
            @Schema(description = "Denúncias que a pessoa fez") int reportsMade) {
    }

    private final JdbcClient jdbc;
    private final ReportService reports;
    private final CryptoService crypto;

    public AdminService(JdbcClient jdbc, ReportService reports, CryptoService crypto) {
        this.jdbc = jdbc;
        this.reports = reports;
        this.crypto = crypto;
    }

    @Transactional(readOnly = true)
    public Overview overview() {
        return jdbc.sql("""
                SELECT (SELECT count(*) FROM reports WHERE status = 'pending') AS pending_reports,
                       (SELECT count(DISTINCT (target_kind, target_id)) FROM reports WHERE status = 'pending') AS reported_items,
                       (SELECT count(*) FROM users WHERE deleted_at IS NULL) AS users,
                       (SELECT count(*) FROM users WHERE deleted_at IS NULL AND created_at > now() - interval '7 days') AS new_users,
                       (SELECT count(*) FROM posts WHERE deleted_at IS NULL AND created_at > now() - interval '7 days') AS posts,
                       (SELECT count(*) FROM reviews WHERE deleted_at IS NULL AND created_at > now() - interval '7 days') AS reviews,
                       (SELECT count(*) FROM comments WHERE deleted_at IS NULL AND created_at > now() - interval '7 days') AS comments,
                       (SELECT count(*) FROM users WHERE deleted_at IS NOT NULL) AS pending_deletion
                """)
                .query((rs, row) -> new Overview(rs.getInt("pending_reports"), rs.getInt("reported_items"), rs.getInt("users"),
                        rs.getInt("new_users"), rs.getInt("posts"), rs.getInt("reviews"), rs.getInt("comments"),
                        rs.getInt("pending_deletion")))
                .single();
    }

    /** Inclui contas com exclusão pedida: a moderação precisa ver também quem saiu. */
    @Transactional(readOnly = true)
    public UserFile userFile(String username) {
        UserFile file = jdbc.sql("""
                SELECT u.id, u.username, u.display_name, u.role, u.level, u.two_factor_enabled_at IS NOT NULL AS two_factor,
                       u.created_at, u.deleted_at,
                       (SELECT count(*) FROM posts WHERE user_id = u.id AND deleted_at IS NULL) AS posts,
                       (SELECT count(*) FROM reviews WHERE user_id = u.id AND deleted_at IS NULL) AS reviews,
                       (SELECT count(*) FROM comments WHERE user_id = u.id AND deleted_at IS NULL) AS comments,
                       (SELECT count(*) FROM reports WHERE reporter_id = u.id) AS reports_made
                FROM users u WHERE u.username = :username
                """)
                .param("username", username)
                .query((rs, row) -> new UserFile(rs.getObject("id", UUID.class), rs.getString("username"),
                        rs.getString("display_name"), null, rs.getString("role"), rs.getInt("level"),
                        rs.getBoolean("two_factor"), rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("deleted_at") == null ? null : rs.getTimestamp("deleted_at").toInstant(),
                        rs.getInt("posts"), rs.getInt("reviews"), rs.getInt("comments"), 0, rs.getInt("reports_made")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuário não encontrado"));
        // O e-mail fica criptografado no banco
        String email = jdbc.sql("SELECT email_encrypted FROM users WHERE id = :id").param("id", file.id())
                .query(String.class).optional().map(crypto::decrypt).orElse(null);
        return new UserFile(file.id(), file.username(), file.displayName(), email, file.role(), file.level(),
                file.twoFactorEnabled(), file.createdAt(), file.deletedAt(), file.posts(), file.reviews(), file.comments(),
                reports.pendingAgainst(file.id()), file.reportsMade());
    }
}
