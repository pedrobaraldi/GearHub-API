package com.gearhub.auth;

import com.gearhub.crypto.CryptoService;
import com.gearhub.mail.MailProperties;
import com.gearhub.mail.MailService;
import com.gearhub.users.User;
import com.gearhub.users.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.HtmlUtils;

/** Recuperação de senha. Só o hash do token fica no banco, e cada link vale uma vez. */
@Service
public class PasswordResetService {

    private static final String PURPOSE = "reset_password";
    private static final Duration LINK_TTL = Duration.ofMinutes(30);

    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final CryptoService crypto;
    private final RateLimiter rateLimiter;
    private final AuthProperties properties;
    private final TurnstileService turnstile;
    private final MailService mail;
    private final MailProperties mailProperties;
    private final JdbcClient jdbc;
    private final UnprovenAccess unproven;

    public PasswordResetService(UserRepository users, PasswordEncoder passwords, CryptoService crypto,
            RateLimiter rateLimiter, AuthProperties properties, TurnstileService turnstile, MailService mail,
            MailProperties mailProperties, JdbcClient jdbc, UnprovenAccess unproven) {
        this.unproven = unproven;
        this.users = users;
        this.passwords = passwords;
        this.crypto = crypto;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.turnstile = turnstile;
        this.mail = mail;
        this.mailProperties = mailProperties;
        this.jdbc = jdbc;
    }

    /** Responde igual com ou sem conta: quem chama não descobre quem está cadastrado. */
    @Transactional
    public void request(AuthRequests.ForgotPassword request, String ip) {
        String email = request.email().strip().toLowerCase(Locale.ROOT);
        String emailHash = crypto.blindIndex(email);
        rateLimiter.check("reset-ip:" + ip, properties.resetLimitPerMinute());
        rateLimiter.check("reset-account:" + emailHash, properties.resetLimitPerMinute());
        turnstile.verify(request.turnstileToken());

        User user = users.findByEmailHashAndDeletedAtIsNull(emailHash).orElse(null);
        if (user == null) {
            return;
        }

        // Um pedido novo cancela os links anteriores
        jdbc.sql("""
                UPDATE email_tokens SET used_at = now()
                WHERE user_id = :user AND purpose = :purpose AND used_at IS NULL
                """).param("user", user.getId()).param("purpose", PURPOSE).update();

        String token = crypto.newToken();
        jdbc.sql("""
                INSERT INTO email_tokens (user_id, token_hash, purpose, expires_at)
                VALUES (:user, :hash, :purpose, :expires)
                """)
                .param("user", user.getId())
                .param("hash", crypto.sha256(token))
                .param("purpose", PURPOSE)
                .param("expires", Timestamp.from(Instant.now().plus(LINK_TTL)))
                .update();

        String link = mailProperties.siteUrl() + "/redefinir-senha?token=" + token;
        // O nome é escrito por quem criou a conta. Sem o e-mail confirmado, pode ser outra pessoa usando o
        // endereço da vítima para mandar um texto de golpe num e-mail de verdade do GearHub
        String name = user.getEmailVerifiedAt() != null ? user.getDisplayName() : "tudo bem";
        String subject = "Redefina sua senha do GearHub";
        if (mailProperties.hasResetTemplate()) {
            // O nome é escrito pela própria pessoa: sem escapar, viraria HTML dentro do e-mail
            mail.sendTemplateInBackground(email, subject, mailProperties.resetTemplate(), Map.of(
                    "NOME", HtmlUtils.htmlEscape(name),
                    "LINK_REDEFINIR", link,
                    "MINUTOS", String.valueOf(LINK_TTL.toMinutes())));
            return;
        }
        mail.sendInBackground(email, subject, """
                Olá, %s.

                Recebemos um pedido para redefinir a senha da sua conta no GearHub.
                Para criar uma senha nova, abra este link em até %d minutos:

                %s

                Se não foi você, ignore este e-mail: sua senha continua a mesma.
                """.formatted(name, LINK_TTL.toMinutes(), link));
    }

    @Transactional
    public void reset(AuthRequests.ResetPassword request) {
        UUID userId = jdbc.sql("""
                SELECT user_id FROM email_tokens
                WHERE token_hash = :hash AND purpose = :purpose AND used_at IS NULL AND expires_at > now()
                FOR UPDATE
                """)
                .param("hash", crypto.sha256(request.token()))
                .param("purpose", PURPOSE)
                .query(UUID.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Este link é inválido ou expirou. Peça um novo."));

        User user = users.findById(userId).filter(found -> found.getDeletedAt() == null)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Este link é inválido ou expirou. Peça um novo."));
        // Abrir o link prova que a pessoa controla o e-mail. Se ele ainda não estava confirmado, o que foi
        // criado antes (2FA, Google ou Discord ligados) pode ser de quem se cadastrou com o e-mail alheio
        if (user.getEmailVerifiedAt() == null) {
            unproven.revoke(user);
        }
        user.changePassword(passwords.encode(request.password()));
        user.markEmailVerified();
        users.saveAndFlush(user);

        // Todos os links pendentes da conta, de redefinição e de troca de e-mail
        jdbc.sql("UPDATE email_tokens SET used_at = now() WHERE user_id = :user AND used_at IS NULL")
                .param("user", userId).update();
        // Quem estava com a sessão aberta sai: pode ser justamente quem invadiu a conta
        jdbc.sql("DELETE FROM sessions WHERE user_id = :user").param("user", userId).update();
    }
}
