package com.gearhub.auth;

import com.gearhub.admin.AuditService;
import com.gearhub.account.AccountService;
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

    /**
     * Resultado do login ou cadastro. Os tokens puros só existem aqui, a caminho do cookie: a sessão, ou,
     * numa conta com verificação em duas etapas, o login em espera que ainda pede o código.
     */
    public record AuthResult(User user, String sessionToken, String challengeToken) {

        public boolean needsCode() {
            return challengeToken != null;
        }
    }

    private final UserRepository users;
    private final SessionRepository sessions;
    private final PasswordEncoder passwords;
    private final CryptoService crypto;
    private final RateLimiter rateLimiter;
    private final AuthProperties properties;
    private final TurnstileService turnstile;
    private final TwoFactorService twoFactor;
    private final AccountService accounts;
    private final AuditService audit;
    private final String dummyHash;

    public AuthService(UserRepository users, SessionRepository sessions, PasswordEncoder passwords,
            CryptoService crypto, RateLimiter rateLimiter, AuthProperties properties,
            TurnstileService turnstile, TwoFactorService twoFactor, AccountService accounts, AuditService audit) {
        this.audit = audit;
        this.users = users;
        this.sessions = sessions;
        this.passwords = passwords;
        this.crypto = crypto;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.turnstile = turnstile;
        this.twoFactor = twoFactor;
        this.accounts = accounts;
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
        return new AuthResult(user, openSession(user, ip, userAgent), null);
    }

    @Transactional
    public AuthResult login(AuthRequests.Login request, String ip, String userAgent) {
        String emailHash = crypto.blindIndex(normalizeEmail(request.email()));
        rateLimiter.check("login-ip:" + ip, properties.loginLimitPerMinute());
        rateLimiter.check("login-account:" + emailHash, properties.loginLimitPerMinute());
        turnstile.verify(request.turnstileToken());

        // Conta com exclusão pedida ainda entra nos 30 dias: entrar cancela a exclusão
        User user = users.findByEmailHash(emailHash)
                .filter(found -> found.getDeletedAt() == null || found.isRestorable())
                .orElse(null);
        String storedHash = user == null || user.getPasswordHash() == null ? dummyHash : user.getPasswordHash();
        // A comparação roda mesmo sem conta, para o tempo de resposta não revelar quem está cadastrado
        boolean matches = passwords.matches(request.password(), storedHash);
        if (user == null || user.getPasswordHash() == null || !matches) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, INVALID_CREDENTIALS);
        }
        return start(user, ip, userAgent);
    }

    /** Senha certa ou volta do Google/Discord: com 2FA ativa, a sessão só nasce depois do código. */
    @Transactional
    public AuthResult start(User user, String ip, String userAgent) {
        if (twoFactor.isEnabled(user.getId())) {
            return new AuthResult(user, null, twoFactor.openChallenge(user));
        }
        return new AuthResult(user, openSession(user, ip, userAgent), null);
    }

    @Transactional
    public void logout(UUID sessionId) {
        sessions.deleteById(sessionId);
    }

    @Transactional(readOnly = true)
    public User currentUser(UUID userId) {
        return users.findById(userId).orElseThrow();
    }

    @Transactional
    public String openSession(User user, String ip, String userAgent) {
        // Login completo (com o código, se houver 2FA) dentro do prazo: a conta volta
        accounts.restore(user);
        audit.record(user.getId(), "auth.login");
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
