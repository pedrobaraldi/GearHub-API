package com.gearhub.auth;

import com.gearhub.admin.AuditService;
import com.gearhub.crypto.CryptoService;
import com.gearhub.users.User;
import com.gearhub.users.UserRepository;
import io.swagger.v3.oas.annotations.media.Schema;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Login do painel de admin (programa de desktop). O site usa cookie com checagem de origem, que um programa
 * instalado não tem; aqui é um token curto, só para admin com 2FA, guardado pelo programa no cofre do Windows.
 * Só o hash fica no banco. Trocar a senha, desligar o 2FA ou apagar a conta revoga todos.
 */
@Service
public class AdminAppTokenService {

    public static final Duration TTL = Duration.ofHours(8);
    private static final String INVALID = "E-mail, senha ou código incorretos.";

    public record Login(String token, Instant expiresAt) {
    }

    @Schema(description = "Aparelho com o painel aberto")
    public record AppToken(UUID id, String deviceName, String ip, Instant createdAt, Instant expiresAt, Instant lastUsedAt,
            Instant revokedAt, @Schema(description = "É o aparelho desta requisição") boolean current) {
    }

    /** Quem o token autentica, e de qual aparelho. */
    public record Holder(UUID tokenId, UUID userId, String role, String deviceName) {
    }

    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final CryptoService crypto;
    private final RateLimiter rateLimiter;
    private final AuthProperties properties;
    private final TwoFactorService twoFactor;
    private final JdbcClient jdbc;
    private final AuditService audit;
    private final String dummyHash;

    public AdminAppTokenService(UserRepository users, PasswordEncoder passwords, CryptoService crypto, RateLimiter rateLimiter,
            AuthProperties properties, TwoFactorService twoFactor, JdbcClient jdbc, AuditService audit) {
        this.users = users;
        this.passwords = passwords;
        this.crypto = crypto;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.twoFactor = twoFactor;
        this.jdbc = jdbc;
        this.audit = audit;
        this.dummyHash = passwords.encode(crypto.newToken());
    }

    /**
     * Senha, código do 2FA e papel de admin, tudo junto. Qualquer falha dá a mesma resposta: quem tenta não
     * descobre se o e-mail existe, se a senha bateu ou se a conta é de admin.
     */
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public Login login(String email, String password, String code, String deviceName, String ip) {
        String emailHash = crypto.blindIndex(email.strip().toLowerCase(Locale.ROOT));
        rateLimiter.check("admin-login-ip:" + ip, properties.loginLimitPerMinute());
        rateLimiter.check("admin-login-account:" + emailHash, properties.loginLimitPerMinute());
        User user = users.findByEmailHashAndDeletedAtIsNull(emailHash).orElse(null);
        String stored = user == null || user.getPasswordHash() == null ? dummyHash : user.getPasswordHash();
        boolean matches = passwords.matches(password, stored);
        if (user == null || user.getPasswordHash() == null || !matches || !"admin".equals(user.getRole())
                || !twoFactor.isEnabled(user.getId()) || code == null || !twoFactor.verify(user.getId(), code)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, INVALID);
        }
        String device = deviceName == null || deviceName.isBlank() ? "Painel de admin" : deviceName.strip();
        device = device.length() > 80 ? device.substring(0, 80) : device;
        String token = crypto.newToken();
        Instant expiresAt = Instant.now().plus(TTL);
        UUID id = jdbc.sql("""
                INSERT INTO admin_app_tokens (user_id, token_hash, device_name, ip_encrypted, expires_at)
                VALUES (:user, :hash, :device, :ip, :expires) RETURNING id
                """)
                .param("user", user.getId()).param("hash", crypto.sha256(token)).param("device", device)
                .param("ip", ip == null ? null : crypto.encrypt(ip), java.sql.Types.VARCHAR)
                .param("expires", Timestamp.from(expiresAt))
                .query(UUID.class).single();
        audit.record(user.getId(), "admin.app_login", "admin_app_token", id, Map.of("device", device));
        return new Login(token, expiresAt);
    }

    /** Token válido: não revogado, no prazo, de uma conta ativa que ainda é admin. */
    @Transactional
    public Optional<Holder> authenticate(String token) {
        Optional<Holder> holder = jdbc.sql("""
                SELECT t.id, t.user_id, u.role, t.device_name FROM admin_app_tokens t JOIN users u ON u.id = t.user_id
                WHERE t.token_hash = :hash AND t.revoked_at IS NULL AND t.expires_at > now()
                  AND u.deleted_at IS NULL AND u.role = 'admin'
                """).param("hash", crypto.sha256(token))
                .query((rs, row) -> new Holder(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                        rs.getString("role"), rs.getString("device_name")))
                .optional();
        holder.ifPresent(found -> jdbc.sql("""
                UPDATE admin_app_tokens SET last_used_at = now()
                WHERE id = :id AND (last_used_at IS NULL OR last_used_at < now() - interval '1 minute')
                """).param("id", found.tokenId()).update());
        return holder;
    }

    /** Os aparelhos com o painel aberto (de todos os admins): um notebook perdido precisa ser cortado por qualquer um da equipe. */
    @Transactional(readOnly = true)
    public List<AppToken> list(UUID currentToken) {
        return jdbc.sql("""
                SELECT t.*, u.username FROM admin_app_tokens t JOIN users u ON u.id = t.user_id
                WHERE t.revoked_at IS NULL AND t.expires_at > now()
                ORDER BY t.created_at DESC LIMIT 200
                """)
                .query((rs, row) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    String ip = rs.getString("ip_encrypted");
                    return new AppToken(id, rs.getString("username") + " · " + rs.getString("device_name"),
                            ip == null ? null : crypto.decrypt(ip), rs.getTimestamp("created_at").toInstant(),
                            rs.getTimestamp("expires_at").toInstant(),
                            rs.getTimestamp("last_used_at") == null ? null : rs.getTimestamp("last_used_at").toInstant(),
                            null, id.equals(currentToken));
                }).list();
    }

    @Transactional
    public void revoke(UUID actorId, UUID tokenId) {
        int changed = jdbc.sql("UPDATE admin_app_tokens SET revoked_at = now(), revoked_by = :actor WHERE id = :id AND revoked_at IS NULL")
                .param("actor", actorId).param("id", tokenId).update();
        if (changed == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Acesso não encontrado ou já encerrado");
        }
        audit.record(actorId, "admin.app_revoke", "admin_app_token", tokenId, Map.of());
    }

    /** Todos os tokens do painel da pessoa (troca de senha, 2FA desligado, conta apagada, e-mail provado por outro). */
    public static void revokeAll(JdbcClient jdbc, UUID userId) {
        jdbc.sql("UPDATE admin_app_tokens SET revoked_at = now() WHERE user_id = :user AND revoked_at IS NULL")
                .param("user", userId).update();
    }
}
