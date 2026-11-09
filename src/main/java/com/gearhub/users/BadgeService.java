package com.gearhub.users;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Insígnias exclusivas: o catálogo fica no banco (V18) e só admin dá ou tira. */
@Service
public class BadgeService {

    @Schema(description = "Insígnia exclusiva")
    public record Badge(@Schema(example = "developer") String key, @Schema(example = "Desenvolvedor") String name,
            @Schema(example = "Equipe do projeto") String description,
            @Schema(description = "Quando o usuário recebeu; nulo no catálogo") OffsetDateTime grantedAt,
            @Schema(description = "Só no Fundador: a ordem de cadastro, de 1 a 100") Integer number) {
    }

    private final JdbcClient jdbc;
    private final UserRepository users;

    public BadgeService(JdbcClient jdbc, UserRepository users) {
        this.jdbc = jdbc;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public List<Badge> catalog() {
        return jdbc.sql("SELECT key, name, description, NULL::timestamptz AS granted_at, NULL::integer AS number FROM badges ORDER BY position")
                .query(Badge.class).list();
    }

    @Transactional(readOnly = true)
    public List<Badge> of(UUID userId) {
        return jdbc.sql("""
                SELECT b.key, b.name, b.description, u.granted_at,
                       CASE WHEN b.key = 'founder' THEN p.founder_number END AS number
                FROM user_badges u
                JOIN badges b ON b.key = u.badge_key
                JOIN users p ON p.id = u.user_id
                WHERE u.user_id = :user ORDER BY b.position
                """)
                .param("user", userId).query(Badge.class).list();
    }

    @Transactional
    public List<Badge> set(UUID adminId, String username, String key, boolean granted) {
        User target = users.findByUsernameAndDeletedAtIsNull(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuário não encontrado"));
        boolean exists = jdbc.sql("SELECT count(*) FROM badges WHERE key = :key")
                .param("key", key).query(Integer.class).single() > 0;
        if (!exists) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Insígnia não encontrada");
        }
        String change = granted
                ? """
                  INSERT INTO user_badges (user_id, badge_key, granted_by) VALUES (:user, :key, :admin)
                  ON CONFLICT DO NOTHING
                  """
                : "DELETE FROM user_badges WHERE user_id = :user AND badge_key = :key";
        var statement = jdbc.sql(change).param("user", target.getId()).param("key", key);
        if (granted) {
            statement = statement.param("admin", adminId);
        }
        statement.update();
        return of(target.getId());
    }
}
