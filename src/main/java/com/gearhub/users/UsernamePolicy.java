package com.gearhub.users;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Quem pode usar um @. Ele é único e fica com quem pegou primeiro; além disso:
 * <ul>
 * <li>quem troca de @ segura o antigo por {@link #HOLD} (só ela pode voltar a ele), para links e menções antigas
 * não levarem logo a outra pessoa;</li>
 * <li>nomes reservados (marca, equipe, sistema) ninguém pega, a não ser a conta a que o admin entregou o nome.</li>
 * </ul>
 * Conta apagada continua com o @ (exclusão lógica), então ele nunca é liberado.
 */
@Component
public class UsernamePolicy {

    public static final Duration HOLD = Duration.ofDays(15);
    public static final String TAKEN = "Esse nome de usuário já está em uso";
    public static final String RESERVED = "Esse nome de usuário é reservado. Escolha outro.";
    public static final String HELD = "Esse nome de usuário foi usado há pouco por outra pessoa e fica guardado por alguns dias. Escolha outro.";

    private final JdbcClient jdbc;

    public UsernamePolicy(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Motivo de o @ não poder ser usado por {@code requester} (nulo no cadastro); vazio se pode. */
    public Optional<String> problem(String username, UUID requester) {
        if (isReserved(username, requester)) {
            return Optional.of(RESERVED);
        }
        boolean taken = jdbc.sql("SELECT count(*) FROM users WHERE username = :name AND (CAST(:me AS uuid) IS NULL OR id <> :me)")
                .param("name", username).param("me", requester, java.sql.Types.OTHER)
                .query(Long.class).single() > 0;
        if (taken) {
            return Optional.of(TAKEN);
        }
        boolean held = jdbc.sql("""
                SELECT count(*) FROM username_history
                WHERE username = :name AND held_until > now() AND (CAST(:me AS uuid) IS NULL OR user_id <> :me)
                """)
                .param("name", username).param("me", requester, java.sql.Types.OTHER)
                .query(Long.class).single() > 0;
        return held ? Optional.of(HELD) : Optional.empty();
    }

    public void require(String username, UUID requester) {
        problem(username, requester).ifPresent(message -> {
            throw new ResponseStatusException(HttpStatus.CONFLICT, message);
        });
    }

    /** Reservado para todo mundo, menos para a conta oficial a que o nome foi entregue. */
    public boolean isReserved(String username, UUID requester) {
        return jdbc.sql("""
                SELECT count(*) FROM reserved_usernames
                WHERE removed_at IS NULL
                  AND (assigned_to IS NULL OR CAST(:me AS uuid) IS NULL OR assigned_to <> :me)
                  AND ((match_type = 'exact' AND pattern = :name)
                    OR (match_type = 'prefix' AND left(:name, length(pattern)) = pattern)
                    OR (match_type = 'contains' AND strpos(:name, pattern) > 0))
                """)
                .param("name", username).param("me", requester, java.sql.Types.OTHER)
                .query(Long.class).single() > 0;
    }

    /** O @ antigo fica guardado para a dona por {@link #HOLD}. */
    public void recordChange(UUID userId, String oldUsername) {
        jdbc.sql("INSERT INTO username_history (user_id, username, held_until) VALUES (:user, :name, :until)")
                .param("user", userId).param("name", oldUsername)
                .param("until", Timestamp.from(Instant.now().plus(HOLD)))
                .update();
    }

    /** @ livre para uma conta nova do login social, a partir do e-mail. Nunca devolve um nome reservado. */
    public String suggest(String base) {
        String clean = base == null ? "" : base.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (clean.length() < 3 || isReserved(clean, null)) {
            clean = "jogador";
        }
        String candidate = clean.length() <= 24 ? clean : clean.substring(0, 24);
        while (problem(candidate, null).isPresent()) {
            candidate = (clean.length() <= 20 ? clean : clean.substring(0, 20))
                    + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        }
        return candidate;
    }
}
