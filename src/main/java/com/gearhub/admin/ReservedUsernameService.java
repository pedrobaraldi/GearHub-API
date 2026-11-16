package com.gearhub.admin;

import com.gearhub.config.FieldValidationException;
import com.gearhub.users.UsernamePolicy;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Nomes reservados (marca, equipe, sistema), para o painel de admin. Nada é apagado: tirar a reserva só a
 * desativa. Entregar um nome a uma conta é o caminho das contas oficiais: só aquela conta pode usá-lo.
 */
@Service
public class ReservedUsernameService {

    private static final Set<String> MATCH_TYPES = Set.of("exact", "prefix", "contains");

    @Schema(description = "Nome reservado")
    public record Reserved(
            @Schema(example = "gearhub") String pattern,
            @Schema(description = "exact (o @ inteiro), prefix (começa com) ou contains (contém)") String matchType,
            String reason,
            Instant createdAt,
            @Schema(description = "@ da conta oficial que recebeu o nome; nulo se ninguém") String assignedTo,
            Instant assignedAt,
            @Schema(description = "Quando a reserva deixou de valer; nulo se vale") Instant removedAt) {
    }

    private final JdbcClient jdbc;
    private final UsernamePolicy usernames;
    private final AuditService audit;

    public ReservedUsernameService(JdbcClient jdbc, UsernamePolicy usernames, AuditService audit) {
        this.jdbc = jdbc;
        this.usernames = usernames;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Reserved> list(boolean includeRemoved) {
        return jdbc.sql("""
                SELECT r.pattern, r.match_type, r.reason, r.created_at, u.username AS assigned_to, r.assigned_at, r.removed_at
                FROM reserved_usernames r LEFT JOIN users u ON u.id = r.assigned_to
                WHERE :all OR r.removed_at IS NULL
                ORDER BY r.pattern
                """)
                .param("all", includeRemoved)
                .query((rs, row) -> new Reserved(rs.getString("pattern"), rs.getString("match_type"), rs.getString("reason"),
                        rs.getTimestamp("created_at").toInstant(), rs.getString("assigned_to"),
                        rs.getTimestamp("assigned_at") == null ? null : rs.getTimestamp("assigned_at").toInstant(),
                        rs.getTimestamp("removed_at") == null ? null : rs.getTimestamp("removed_at").toInstant()))
                .list();
    }

    /** Reserva um nome (ou reativa uma reserva desativada). Quem já usa o @ continua com ele. */
    @Transactional
    public void reserve(UUID adminId, String pattern, String matchType, String reason) {
        String clean = pattern == null ? "" : pattern.strip().toLowerCase(java.util.Locale.ROOT);
        String type = matchType == null || matchType.isBlank() ? "exact" : matchType;
        if (!clean.matches("[a-z0-9]{2,30}")) {
            throw new FieldValidationException(Map.of("pattern", "Use de 2 a 30 letras minúsculas ou números"));
        }
        if (!MATCH_TYPES.contains(type)) {
            throw new FieldValidationException(Map.of("matchType", "Use exact, prefix ou contains"));
        }
        try {
            jdbc.sql("""
                    INSERT INTO reserved_usernames (pattern, match_type, reason, created_by)
                    VALUES (:pattern, :type, :reason, :admin)
                    """)
                    .param("pattern", clean).param("type", type).param("reason", reason, java.sql.Types.VARCHAR)
                    .param("admin", adminId).update();
        } catch (DuplicateKeyException e) {
            int reactivated = jdbc.sql("""
                    UPDATE reserved_usernames SET removed_at = NULL, removed_by = NULL, match_type = :type,
                           reason = coalesce(:reason, reason)
                    WHERE pattern = :pattern AND removed_at IS NOT NULL
                    """)
                    .param("pattern", clean).param("type", type).param("reason", reason, java.sql.Types.VARCHAR).update();
            if (reactivated == 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Esse nome já está reservado");
            }
        }
        audit.record(adminId, "admin.username_reserve", null, null, Map.of("pattern", clean, "matchType", type));
    }

    /** A reserva deixa de valer (exclusão lógica). Se o nome estava entregue a uma conta, ela continua com o @. */
    @Transactional
    public void release(UUID adminId, String pattern) {
        int changed = jdbc.sql("""
                UPDATE reserved_usernames SET removed_at = now(), removed_by = :admin
                WHERE pattern = :pattern AND removed_at IS NULL
                """).param("pattern", pattern).param("admin", adminId).update();
        if (changed == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Reserva não encontrada");
        }
        audit.record(adminId, "admin.username_release", null, null, Map.of("pattern", pattern));
    }

    /**
     * Entrega um nome reservado exato a uma conta (conta oficial): o @ dela passa a ser esse nome. O @ antigo
     * fica guardado por 15 dias, como em qualquer troca.
     */
    @Transactional
    public void assign(UUID adminId, String pattern, String username) {
        String type = jdbc.sql("SELECT match_type FROM reserved_usernames WHERE pattern = :pattern AND removed_at IS NULL")
                .param("pattern", pattern).query(String.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Reserva não encontrada"));
        if (!"exact".equals(type)) {
            throw new FieldValidationException(Map.of("pattern", "Só um nome exato pode ser entregue a uma conta"));
        }
        Map<String, Object> target = jdbc.sql("SELECT id, username FROM users WHERE username = :username AND deleted_at IS NULL")
                .param("username", username).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuário não encontrado"));
        UUID userId = (UUID) target.get("id");
        String before = (String) target.get("username");
        if (!pattern.equals(before)) {
            // A reserva não barra a própria conta, mas o @ ainda precisa estar livre de outras pessoas
            jdbc.sql("UPDATE reserved_usernames SET assigned_to = :user, assigned_at = now() WHERE pattern = :pattern")
                    .param("user", userId).param("pattern", pattern).update();
            usernames.require(pattern, userId);
            usernames.recordChange(userId, before);
            jdbc.sql("UPDATE users SET username = :pattern, updated_at = now() WHERE id = :user")
                    .param("pattern", pattern).param("user", userId).update();
        } else {
            jdbc.sql("UPDATE reserved_usernames SET assigned_to = :user, assigned_at = now() WHERE pattern = :pattern")
                    .param("user", userId).param("pattern", pattern).update();
        }
        audit.record(adminId, "admin.username_assign", "user", userId, Map.of("de", before, "para", pattern));
    }
}
