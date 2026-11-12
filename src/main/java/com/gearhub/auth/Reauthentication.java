package com.gearhub.auth;

import com.gearhub.users.User;
import java.time.Duration;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Prova de que quem pede uma ação sensível é a dona da conta, e não só alguém com a sessão aberta (cookie
 * vazado, computador compartilhado). Com senha, é a senha. Sem senha (só Google ou Discord), é ter entrado
 * há pouco: um cookie roubado costuma ser antigo, e entrar de novo exige passar pelo provedor.
 */
@Component
public class Reauthentication {

    public static final Duration RECENT_LOGIN = Duration.ofMinutes(10);
    public static final String LOGIN_AGAIN =
            "Por segurança, saia e entre de novo com Google ou Discord, e repita em seguida.";

    private final PasswordEncoder passwords;
    private final JdbcClient jdbc;

    public Reauthentication(PasswordEncoder passwords, JdbcClient jdbc) {
        this.passwords = passwords;
        this.jdbc = jdbc;
    }

    /** Senha certa, quando a conta tem uma. Sem senha, sempre falso: use {@link #requireRecentLogin}. */
    public boolean passwordMatches(User user, String password) {
        return user.getPasswordHash() != null && password != null && passwords.matches(password, user.getPasswordHash());
    }

    /** Conta sem senha: a sessão atual precisa ter nascido há pouco. Com senha, não faz nada. */
    public void requireRecentLogin(User user, UUID sessionId) {
        if (user.getPasswordHash() != null) {
            return;
        }
        boolean recent = sessionId != null && jdbc.sql("""
                SELECT count(*) FROM sessions
                WHERE id = :id AND user_id = :user AND created_at > now() - make_interval(secs => :seconds)
                """)
                .param("id", sessionId).param("user", user.getId()).param("seconds", RECENT_LOGIN.toSeconds())
                .query(Long.class).single() > 0;
        if (!recent) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, LOGIN_AGAIN);
        }
    }
}
