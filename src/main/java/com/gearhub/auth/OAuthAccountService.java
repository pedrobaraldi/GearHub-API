package com.gearhub.auth;

import com.gearhub.admin.AuditService;
import com.gearhub.crypto.CryptoService;
import com.gearhub.users.User;
import com.gearhub.users.UserRepository;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Liga uma conta do Google ou do Discord a um usuário do GearHub. */
@Service
public class OAuthAccountService {

    /** O que cada provedor informa sobre a pessoa, e os códigos de erro dele. */
    public record OAuthProfile(String provider, String subject, String email, boolean emailVerified, String name,
            String failedCode, String unverifiedCode) {
    }

    private final UserRepository users;
    private final CryptoService crypto;
    private final JdbcClient jdbc;
    private final AuditService audit;

    public OAuthAccountService(UserRepository users, CryptoService crypto, JdbcClient jdbc, AuditService audit) {
        this.audit = audit;
        this.users = users;
        this.crypto = crypto;
        this.jdbc = jdbc;
    }

    @Transactional
    public User resolve(OAuthProfile profile) {
        String subjectHash = crypto.blindIndex(profile.provider() + ":" + profile.subject());

        Optional<UUID> linked = jdbc.sql(
                "SELECT user_id FROM oauth_accounts WHERE provider = :provider AND provider_user_id_hash = :hash")
                .param("provider", profile.provider())
                .param("hash", subjectHash).query(UUID.class).optional();
        if (linked.isPresent()) {
            return users.findById(linked.get())
                    .filter(user -> user.getDeletedAt() == null || user.isRestorable())
                    .orElseThrow(() -> new OAuthLoginException(profile.failedCode(), null));
        }

        // Só liga contas ou cria uma nova quando o provedor confirma o e-mail
        if (!profile.emailVerified() || profile.email() == null) {
            throw new OAuthLoginException(profile.unverifiedCode(), null);
        }
        String email = profile.email().strip().toLowerCase(Locale.ROOT);
        String emailHash = crypto.blindIndex(email);
        Optional<User> existing = users.findByEmailHash(emailHash)
                .filter(found -> found.getDeletedAt() == null || found.isRestorable());
        existing.filter(found -> found.getEmailVerifiedAt() == null).ifPresent(this::secureUnverifiedAccount);
        User user = existing.orElseGet(() -> createUser(profile.name(), email, emailHash));
        user.markEmailVerified();

        jdbc.sql("""
                INSERT INTO oauth_accounts (user_id, provider, provider_user_id_encrypted, provider_user_id_hash)
                VALUES (:user, :provider, :encrypted, :hash)
                """)
                .param("user", user.getId())
                .param("provider", profile.provider())
                .param("encrypted", crypto.encrypt(profile.subject()))
                .param("hash", subjectHash)
                .update();
        return user;
    }

    /**
     * Proteção contra sequestro antecipado de conta: alguém pode ter criado conta com o e-mail de outra pessoa
     * (sem confirmar o e-mail) e esperado a dona entrar pelo Google. Quem chega aqui provou ser dona do e-mail:
     * a senha e o 2FA criados sem essa prova deixam de valer, e todas as sessões abertas são encerradas.
     * A pessoa cria uma senha nova em Configurações, se quiser.
     */
    private void secureUnverifiedAccount(User user) {
        boolean hadPassword = user.getPasswordHash() != null;
        if (hadPassword) {
            user.changePassword(null);
        }
        jdbc.sql("DELETE FROM sessions WHERE user_id = :user").param("user", user.getId()).update();
        int twoFactor = jdbc.sql("""
                UPDATE users SET totp_secret_encrypted = NULL, two_factor_enabled_at = NULL, totp_last_step = NULL
                WHERE id = :user AND totp_secret_encrypted IS NOT NULL
                """).param("user", user.getId()).update();
        if (twoFactor > 0) {
            jdbc.sql("DELETE FROM two_factor_recovery_codes WHERE user_id = :user").param("user", user.getId()).update();
            jdbc.sql("DELETE FROM user_badges WHERE user_id = :user AND badge_key = 'protected'").param("user", user.getId()).update();
            jdbc.sql("SELECT refresh_user_progress(:user)").param("user", user.getId()).query().listOfRows();
        }
        audit.record(user.getId(), "account.email_proven",
                null, null, java.util.Map.of("passwordRemoved", hadPassword, "twoFactorRemoved", twoFactor > 0));
    }

    private User createUser(String name, String email, String emailHash) {
        String displayName = name == null || name.isBlank() ? email.substring(0, email.indexOf('@')) : name;
        User user = new User(uniqueUsername(email), truncate(displayName.strip(), 60), email, emailHash, null);
        return users.saveAndFlush(user);
    }

    private String uniqueUsername(String email) {
        String base = email.substring(0, email.indexOf('@')).replaceAll("[^a-z0-9]", "");
        String candidate = base.length() < 3 ? "usuario" : truncate(base, 24);
        while (users.existsByUsername(candidate)) {
            candidate = truncate(base.length() < 3 ? "usuario" : base, 20)
                    + UUID.randomUUID().toString().substring(0, 6).replace("-", "");
        }
        return candidate;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
