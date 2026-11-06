package com.gearhub.auth;

import com.gearhub.crypto.CryptoService;
import com.gearhub.users.User;
import com.gearhub.users.UserRepository;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthService {

    private static final String INVALID_CREDENTIALS = "E-mail ou senha incorretos";

    /** Resultado do login ou cadastro. O token puro só existe aqui, a caminho do cookie. */
    public record AuthResult(User user, String token) {
    }

    private final UserRepository users;
    private final SessionRepository sessions;
    private final PasswordEncoder passwords;
    private final CryptoService crypto;
    private final RateLimiter rateLimiter;
    private final AuthProperties properties;
    private final TurnstileService turnstile;
    private final String dummyHash;

    public AuthService(UserRepository users, SessionRepository sessions, PasswordEncoder passwords,
            CryptoService crypto, RateLimiter rateLimiter, AuthProperties properties,
            TurnstileService turnstile) {
        this.users = users;
        this.sessions = sessions;
        this.passwords = passwords;
        this.crypto = crypto;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.turnstile = turnstile;
        this.dummyHash = passwords.encode(crypto.newToken());
    }

    @Transactional
    public AuthResult register(AuthRequests.Register request, String ip, String userAgent) {
        rateLimiter.check("register:" + ip, properties.registerLimitPerMinute());
        turnstile.verify(request.turnstileToken());

        String email = normalizeEmail(request.email());
        String emailHash = crypto.blindIndex(email);
        if (users.existsByUsername(request.username())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Esse nome de usuário já está em uso");
        }
        if (users.existsByEmailHash(emailHash)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Esse e-mail já tem uma conta");
        }

        User user = new User(request.username(), request.displayName().strip(), email, emailHash,
                passwords.encode(request.password()));
        try {
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // Dois cadastros iguais ao mesmo tempo: o índice único do banco decide
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Esse e-mail ou nome de usuário já está em uso");
        }
        return new AuthResult(user, openSession(user, ip, userAgent));
    }

    @Transactional
    public AuthResult login(AuthRequests.Login request, String ip, String userAgent) {
        String emailHash = crypto.blindIndex(normalizeEmail(request.email()));
        rateLimiter.check("login-ip:" + ip, properties.loginLimitPerMinute());
        rateLimiter.check("login-account:" + emailHash, properties.loginLimitPerMinute());
        turnstile.verify(request.turnstileToken());

        User user = users.findByEmailHashAndDeletedAtIsNull(emailHash).orElse(null);
        String storedHash = user == null || user.getPasswordHash() == null ? dummyHash : user.getPasswordHash();
        // A comparação roda mesmo sem conta, para o tempo de resposta não revelar quem está cadastrado
        boolean matches = passwords.matches(request.password(), storedHash);
        if (user == null || user.getPasswordHash() == null || !matches) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, INVALID_CREDENTIALS);
        }
        return new AuthResult(user, openSession(user, ip, userAgent));
    }

    @Transactional
    public String openOAuthSession(User user, String ip, String userAgent) {
        return openSession(user, ip, userAgent);
    }

    @Transactional
    public void logout(UUID sessionId) {
        sessions.deleteById(sessionId);
    }

    @Transactional(readOnly = true)
    public User currentUser(UUID userId) {
        return users.findById(userId).orElseThrow();
    }

    public String openSession(User user, String ip, String userAgent) {
        String token = crypto.newToken();
        Instant expiresAt = Instant.now().plus(properties.sessionTtl());
        sessions.save(new UserSession(user, crypto.sha256(token), ip, truncate(userAgent), expiresAt));
        return token;
    }

    private static String normalizeEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    private static String truncate(String userAgent) {
        return userAgent == null || userAgent.length() <= 300 ? userAgent : userAgent.substring(0, 300);
    }
}
