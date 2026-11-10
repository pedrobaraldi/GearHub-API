package com.gearhub.auth;

import com.gearhub.config.FieldValidationException;
import com.gearhub.crypto.CryptoService;
import com.gearhub.users.User;
import com.gearhub.users.UserRepository;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Verificação em duas etapas com app autenticador. O segredo fica criptografado, os códigos de recuperação
 * só como HMAC, e cada código vale uma vez.
 */
@Service
public class TwoFactorService {

    public static final Duration CHALLENGE_TTL = Duration.ofMinutes(5);
    private static final int MAX_ATTEMPTS = 5;
    private static final int ATTEMPTS_PER_MINUTE = 5;
    private static final int RECOVERY_CODES = 10;
    // Sem 0/o, 1/l/i: o código é lido no papel e digitado à mão
    private static final String RECOVERY_ALPHABET = "23456789abcdefghjkmnpqrstuvwxyz";
    private static final String ISSUER = "GearHub";

    private static final String INVALID_CODE = "Código inválido. Confira o app e tente de novo.";
    private static final String EXPIRED = "A verificação expirou. Entre de novo.";

    public record Setup(String secret, String otpauthUri) {
    }

    public record Status(boolean enabled, int recoveryCodesLeft) {
    }

    private final UserRepository users;
    private final CryptoService crypto;
    private final PasswordEncoder passwords;
    private final RateLimiter rateLimiter;
    private final AuthProperties properties;
    private final JdbcClient jdbc;
    private final SecureRandom random = new SecureRandom();

    public TwoFactorService(UserRepository users, CryptoService crypto, PasswordEncoder passwords,
            RateLimiter rateLimiter, AuthProperties properties, JdbcClient jdbc) {
        this.users = users;
        this.crypto = crypto;
        this.passwords = passwords;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.jdbc = jdbc;
    }

    // ---------- Login ----------

    /** Senha certa numa conta com 2FA: guarda o login em espera e devolve o token do cookie. */
    @Transactional
    public String openChallenge(User user) {
        jdbc.sql("DELETE FROM two_factor_challenges WHERE expires_at < now()").update();
        String token = crypto.newToken();
        jdbc.sql("""
                INSERT INTO two_factor_challenges (token_hash, user_id, expires_at)
                VALUES (:hash, :user, :expires)
                """)
                .param("hash", crypto.sha256(token))
                .param("user", user.getId())
                .param("expires", Timestamp.from(Instant.now().plus(CHALLENGE_TTL)))
                .update();
        return token;
    }

    /**
     * Confere o código do login em espera. Erro não desfaz a transação: a tentativa errada precisa ficar contada.
     */
    @Transactional(noRollbackFor = {ResponseStatusException.class, FieldValidationException.class})
    public User completeChallenge(String token, String code, String ip) {
        if (token == null || token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, EXPIRED);
        }
        String hash = crypto.sha256(token);
        Map<String, Object> challenge = jdbc.sql("""
                SELECT user_id, attempts FROM two_factor_challenges
                WHERE token_hash = :hash AND expires_at > now()
                FOR UPDATE
                """).param("hash", hash).query().listOfRows().stream().findFirst().orElse(null);
        if (challenge == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, EXPIRED);
        }
        UUID userId = (UUID) challenge.get("user_id");
        rateLimiter.check("2fa-ip:" + ip, properties.loginLimitPerMinute());
        rateLimiter.check("2fa-account:" + userId, ATTEMPTS_PER_MINUTE);

        User user = users.findById(userId).filter(found -> found.getDeletedAt() == null)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, EXPIRED));
        if (!acceptsTotpOrRecovery(userId, code)) {
            int attempts = ((Number) challenge.get("attempts")).intValue() + 1;
            if (attempts >= MAX_ATTEMPTS) {
                deleteChallenge(hash);
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Muitas tentativas com o código errado. Entre de novo.");
            }
            jdbc.sql("UPDATE two_factor_challenges SET attempts = :attempts WHERE token_hash = :hash")
                    .param("attempts", attempts).param("hash", hash).update();
            throw new FieldValidationException(Map.of("code", INVALID_CODE));
        }
        deleteChallenge(hash);
        return user;
    }

    private void deleteChallenge(String hash) {
        jdbc.sql("DELETE FROM two_factor_challenges WHERE token_hash = :hash").param("hash", hash).update();
    }

    // ---------- Configurações ----------

    @Transactional(readOnly = true)
    public Status status(UUID userId) {
        return new Status(isEnabled(userId), recoveryCodesLeft(userId));
    }

    /** Gera um segredo novo, ainda inativo: só vale depois que a pessoa confirma um código do app. */
    @Transactional
    public Setup startSetup(UUID userId) {
        rateLimiter.check("2fa-manage:" + userId, ATTEMPTS_PER_MINUTE);
        if (isEnabled(userId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A verificação em duas etapas já está ativa.");
        }
        User user = users.findById(userId).orElseThrow();
        String secret = Totp.newSecret();
        jdbc.sql("""
                UPDATE users SET totp_secret_encrypted = :secret, totp_last_step = NULL, two_factor_enabled_at = NULL
                WHERE id = :id
                """).param("secret", crypto.encrypt(secret)).param("id", userId).update();
        String label = URLEncoder.encode(ISSUER + ":" + user.getUsername(), StandardCharsets.UTF_8).replace("+", "%20");
        String uri = "otpauth://totp/" + label + "?secret=" + secret + "&issuer=" + ISSUER
                + "&algorithm=SHA1&digits=6&period=" + Totp.PERIOD_SECONDS;
        return new Setup(secret, uri);
    }

    /** Confirma o primeiro código, ativa e encerra as outras sessões: quem já estava dentro sem o código sai. */
    @Transactional
    public List<String> enable(UUID userId, UUID currentSession, String code) {
        rateLimiter.check("2fa-manage:" + userId, ATTEMPTS_PER_MINUTE);
        if (isEnabled(userId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A verificação em duas etapas já está ativa.");
        }
        if (secretOf(userId) == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Comece a configuração de novo.");
        }
        if (!acceptsTotp(userId, code)) {
            throw new FieldValidationException(Map.of("code", INVALID_CODE));
        }
        jdbc.sql("UPDATE users SET two_factor_enabled_at = now() WHERE id = :id").param("id", userId).update();
        jdbc.sql("DELETE FROM sessions WHERE user_id = :user AND id <> :current")
                .param("user", userId).param("current", currentSession).update();
        return newRecoveryCodes(userId);
    }

    @Transactional
    public List<String> regenerateRecoveryCodes(UUID userId, String code) {
        rateLimiter.check("2fa-manage:" + userId, ATTEMPTS_PER_MINUTE);
        requireEnabled(userId);
        if (!acceptsTotpOrRecovery(userId, code)) {
            throw new FieldValidationException(Map.of("code", INVALID_CODE));
        }
        return newRecoveryCodes(userId);
    }

    /** Desligar pede a senha (quando a conta tem uma) e um código: uma sessão roubada sozinha não basta. */
    @Transactional
    public void disable(UUID userId, String password, String code) {
        rateLimiter.check("2fa-manage:" + userId, ATTEMPTS_PER_MINUTE);
        requireEnabled(userId);
        User user = users.findById(userId).orElseThrow();
        if (user.getPasswordHash() != null
                && (password == null || !passwords.matches(password, user.getPasswordHash()))) {
            throw new FieldValidationException(Map.of("password", "Senha incorreta"));
        }
        if (!acceptsTotpOrRecovery(userId, code)) {
            throw new FieldValidationException(Map.of("code", INVALID_CODE));
        }
        jdbc.sql("""
                UPDATE users SET totp_secret_encrypted = NULL, totp_last_step = NULL, two_factor_enabled_at = NULL
                WHERE id = :id
                """).param("id", userId).update();
        jdbc.sql("DELETE FROM two_factor_recovery_codes WHERE user_id = :user").param("user", userId).update();
    }

    // ---------- Códigos ----------

    private boolean acceptsTotpOrRecovery(UUID userId, String code) {
        String clean = normalize(code);
        if (clean.matches("\\d{6}")) {
            return acceptsTotp(userId, clean);
        }
        return clean.length() == 10 && jdbc.sql("""
                UPDATE two_factor_recovery_codes SET used_at = now()
                WHERE user_id = :user AND code_hash = :hash AND used_at IS NULL
                """).param("user", userId).param("hash", recoveryHash(clean)).update() == 1;
    }

    /** Aceita o intervalo atual e um de cada lado, para relógios um pouco fora de hora. */
    private boolean acceptsTotp(UUID userId, String code) {
        String clean = normalize(code);
        String secret = secretOf(userId);
        if (secret == null || !clean.matches("\\d{6}")) {
            return false;
        }
        long now = Totp.currentStep();
        for (long step = now - 1; step <= now + 1; step++) {
            if (Totp.code(secret, step).equals(clean)) {
                // Atômico: dois pedidos com o mesmo código ao mesmo tempo, só um passa
                return jdbc.sql("""
                        UPDATE users SET totp_last_step = :step
                        WHERE id = :id AND (totp_last_step IS NULL OR totp_last_step < :step)
                        """).param("step", step).param("id", userId).update() == 1;
            }
        }
        return false;
    }

    private List<String> newRecoveryCodes(UUID userId) {
        jdbc.sql("DELETE FROM two_factor_recovery_codes WHERE user_id = :user").param("user", userId).update();
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < RECOVERY_CODES; i++) {
            StringBuilder code = new StringBuilder();
            for (int j = 0; j < 10; j++) {
                code.append(RECOVERY_ALPHABET.charAt(random.nextInt(RECOVERY_ALPHABET.length())));
            }
            jdbc.sql("INSERT INTO two_factor_recovery_codes (user_id, code_hash) VALUES (:user, :hash)")
                    .param("user", userId).param("hash", recoveryHash(code.toString())).update();
            codes.add(code.substring(0, 5) + "-" + code.substring(5));
        }
        return codes;
    }

    private String recoveryHash(String code) {
        return crypto.blindIndex("2fa-recovery:" + code);
    }

    private static String normalize(String code) {
        return code == null ? "" : code.replaceAll("[\\s-]", "").toLowerCase(Locale.ROOT);
    }

    private String secretOf(UUID userId) {
        return jdbc.sql("SELECT totp_secret_encrypted FROM users WHERE id = :id")
                .param("id", userId).query(String.class).optional()
                .map(crypto::decrypt).orElse(null);
    }

    public boolean isEnabled(UUID userId) {
        return jdbc.sql("SELECT two_factor_enabled_at IS NOT NULL FROM users WHERE id = :id")
                .param("id", userId).query(Boolean.class).single();
    }

    private void requireEnabled(UUID userId) {
        if (!isEnabled(userId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A verificação em duas etapas não está ativa.");
        }
    }

    private int recoveryCodesLeft(UUID userId) {
        return jdbc.sql("SELECT count(*) FROM two_factor_recovery_codes WHERE user_id = :user AND used_at IS NULL")
                .param("user", userId).query(Integer.class).single();
    }
}
