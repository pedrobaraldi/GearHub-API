package com.gearhub.account;

import com.gearhub.auth.RateLimiter;
import com.gearhub.auth.TwoFactorService;
import com.gearhub.config.FieldValidationException;
import com.gearhub.crypto.CryptoService;
import com.gearhub.reviews.ContentService;
import com.gearhub.users.User;
import com.gearhub.users.UserRepository;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Conta nas Configurações: e-mail, senha, contas ligadas, sessões e exclusão. Ações sensíveis pedem a senha
 * (quando a conta tem uma) e o código do app (quando a verificação em duas etapas está ativa).
 */
@Service
public class AccountService {

    private static final int ATTEMPTS_PER_MINUTE = 5;

    @Schema(description = "Resumo da conta para a tela de Configurações")
    public record Overview(String email, boolean emailVerified, boolean hasPassword,
            @Schema(description = "Google e/ou Discord ligados", example = "[\"google\"]") List<String> providers,
            boolean twoFactorEnabled) {
    }

    @Schema(description = "Sessão aberta em um aparelho")
    public record SessionInfo(UUID id, @Schema(example = "Chrome no Windows") String device, String ip,
            Instant createdAt, @Schema(description = "É a sessão deste navegador") boolean current) {
    }

    @Schema(description = "Exclusão pedida: a conta some do site e é apagada de vez nesta data")
    public record Deletion(Instant deleteAt, int graceDays) {
    }

    private final UserRepository users;
    private final JdbcClient jdbc;
    private final PasswordEncoder passwords;
    private final CryptoService crypto;
    private final TwoFactorService twoFactor;
    private final RateLimiter rateLimiter;
    private final ContentService content;

    public AccountService(UserRepository users, JdbcClient jdbc, PasswordEncoder passwords, CryptoService crypto,
            TwoFactorService twoFactor, RateLimiter rateLimiter, ContentService content) {
        this.users = users;
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.crypto = crypto;
        this.twoFactor = twoFactor;
        this.rateLimiter = rateLimiter;
        this.content = content;
    }

    @Transactional(readOnly = true)
    public Overview overview(UUID userId) {
        User user = users.findById(userId).orElseThrow();
        return new Overview(user.getEmail(), user.getEmailVerifiedAt() != null, user.getPasswordHash() != null,
                providers(userId), user.isTwoFactorEnabled());
    }

    // ---------- Senha e e-mail ----------

    /** Troca a senha, ou cria uma para quem só entra com Google ou Discord. Encerra as outras sessões. */
    @Transactional
    public void changePassword(UUID userId, UUID currentSession, AccountRequests.ChangePassword request) {
        User user = limited(userId);
        confirm(user, request.currentPassword(), request.code(), "currentPassword");
        user.changePassword(passwords.encode(request.password()));
        users.save(user);
        signOutOthers(userId, currentSession);
    }

    @Transactional
    public void changeEmail(UUID userId, AccountRequests.ChangeEmail request) {
        User user = limited(userId);
        if (user.getPasswordHash() == null) {
            throw new FieldValidationException(Map.of("password",
                    "Crie uma senha antes de trocar o e-mail: quem entra só com Google ou Discord confirma com ela."));
        }
        confirm(user, request.password(), request.code(), "password");
        String email = request.email().strip().toLowerCase(Locale.ROOT);
        String emailHash = crypto.blindIndex(email);
        if (emailHash.equals(user.getEmailHash())) {
            throw new FieldValidationException(Map.of("email", "Esse já é o seu e-mail"));
        }
        if (users.findByEmailHash(emailHash).isPresent()) {
            throw new FieldValidationException(Map.of("email", "Esse e-mail já tem uma conta"));
        }
        user.changeEmail(email, emailHash);
        try {
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw new FieldValidationException(Map.of("email", "Esse e-mail já tem uma conta"));
        }
    }

    // ---------- Contas ligadas ----------

    /** Desliga o Google ou o Discord, desde que sobre outro jeito de entrar. */
    @Transactional
    public void disconnect(UUID userId, String provider) {
        User user = limited(userId);
        List<String> linked = providers(userId);
        if (!linked.contains(provider)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Essa conta não está ligada");
        }
        if (user.getPasswordHash() == null && linked.size() == 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Crie uma senha antes: sem ela, você ficaria sem jeito de entrar.");
        }
        jdbc.sql("DELETE FROM oauth_accounts WHERE user_id = :user AND provider = :provider")
                .param("user", userId).param("provider", provider).update();
    }

    private List<String> providers(UUID userId) {
        return jdbc.sql("SELECT DISTINCT provider FROM oauth_accounts WHERE user_id = :user ORDER BY provider")
                .param("user", userId).query(String.class).list();
    }

    // ---------- Sessões ----------

    @Transactional(readOnly = true)
    public List<SessionInfo> sessions(UUID userId, UUID currentSession) {
        return jdbc.sql("""
                SELECT id, ip_encrypted, user_agent_encrypted, created_at FROM sessions
                WHERE user_id = :user AND expires_at > now() ORDER BY created_at DESC
                """)
                .param("user", userId)
                .query((rs, row) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    return new SessionInfo(id, Devices.describe(decrypt(rs.getString("user_agent_encrypted"))),
                            decrypt(rs.getString("ip_encrypted")), rs.getTimestamp("created_at").toInstant(),
                            id.equals(currentSession));
                })
                .list();
    }

    @Transactional
    public void signOut(UUID userId, UUID sessionId) {
        int removed = jdbc.sql("DELETE FROM sessions WHERE id = :id AND user_id = :user")
                .param("id", sessionId).param("user", userId).update();
        if (removed == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Sessão não encontrada");
        }
    }

    @Transactional
    public int signOutOthers(UUID userId, UUID currentSession) {
        return jdbc.sql("DELETE FROM sessions WHERE user_id = :user AND id <> :current")
                .param("user", userId).param("current", currentSession).update();
    }

    private String decrypt(String value) {
        if (value == null) {
            return null;
        }
        try {
            return crypto.decrypt(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ---------- Exclusão ----------

    /**
     * Some do site na hora e é apagada de vez depois do prazo. Entrar de novo antes disso cancela.
     * As sessões são encerradas, inclusive esta.
     */
    @Transactional
    public Deletion requestDeletion(UUID userId, AccountRequests.DeleteAccount request) {
        User user = limited(userId);
        Map<String, String> errors = new LinkedHashMap<>();
        if (!user.getUsername().equalsIgnoreCase(request.confirmation().strip())) {
            errors.put("confirmation", "Digite exatamente o seu nome de usuário");
        }
        errors.putAll(confirmationErrors(user, request.password(), request.code(), "password"));
        if (!errors.isEmpty()) {
            throw new FieldValidationException(errors);
        }
        Instant now = Instant.now();
        jdbc.sql("UPDATE users SET deleted_at = :now WHERE id = :id")
                .param("now", java.sql.Timestamp.from(now)).param("id", userId).update();
        jdbc.sql("DELETE FROM sessions WHERE user_id = :user").param("user", userId).update();
        // As reviews deixam de contar na nota dos produtos enquanto a conta está apagada
        content.refreshScoresOfAuthor(userId);
        return new Deletion(now.plus(User.DELETION_GRACE_DAYS, ChronoUnit.DAYS), User.DELETION_GRACE_DAYS);
    }

    /** Entrou de novo dentro do prazo: a conta volta como estava. */
    @Transactional
    public void restore(User user) {
        if (user.getDeletedAt() == null) {
            return;
        }
        jdbc.sql("UPDATE users SET deleted_at = NULL WHERE id = :id").param("id", user.getId()).update();
        content.refreshScoresOfAuthor(user.getId());
    }

    /** Passado o prazo, apaga de vez: o banco leva junto posts, reviews, comentários e o resto. */
    @Transactional
    public int purgeExpired() {
        List<UUID> expired = jdbc.sql("""
                SELECT id FROM users WHERE deleted_at < now() - make_interval(days => :days)
                """).param("days", User.DELETION_GRACE_DAYS).query(UUID.class).list();
        for (UUID userId : expired) {
            List<UUID> products = jdbc.sql("SELECT DISTINCT product_id FROM reviews WHERE user_id = :user")
                    .param("user", userId).query(UUID.class).list();
            jdbc.sql("DELETE FROM users WHERE id = :id").param("id", userId).update();
            products.forEach(content::refreshProductScore);
        }
        return expired.size();
    }

    // ---------- Confirmação ----------

    private User limited(UUID userId) {
        rateLimiter.check("account:" + userId, ATTEMPTS_PER_MINUTE);
        return users.findById(userId).orElseThrow();
    }

    private void confirm(User user, String password, String code, String passwordField) {
        Map<String, String> errors = confirmationErrors(user, password, code, passwordField);
        if (!errors.isEmpty()) {
            throw new FieldValidationException(errors);
        }
    }

    private Map<String, String> confirmationErrors(User user, String password, String code, String passwordField) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (user.getPasswordHash() != null
                && (password == null || !passwords.matches(password, user.getPasswordHash()))) {
            errors.put(passwordField, "Senha incorreta");
        }
        // O código só é conferido (e gasto) depois que a senha bateu
        if (errors.isEmpty() && user.isTwoFactorEnabled()
                && (code == null || code.isBlank() || !twoFactor.verify(user.getId(), code))) {
            errors.put("code", "Código inválido. Confira o app e tente de novo.");
        }
        return errors;
    }
}
