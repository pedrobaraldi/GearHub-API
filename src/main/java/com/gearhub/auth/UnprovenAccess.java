package com.gearhub.auth;

import com.gearhub.admin.AuditService;
import com.gearhub.users.User;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Proteção contra sequestro antecipado de conta. Alguém pode criar conta com o e-mail de outra pessoa (sem
 * confirmar o e-mail) e esperar a dona aparecer. Quando ela prova que o e-mail é dela (login pelo Google ou
 * Discord, ou link de redefinição de senha), todo jeito de entrar criado antes dessa prova deixa de valer:
 * senha, verificação em duas etapas, contas do Google ou Discord ligadas, sessões e links pendentes.
 * Quem chama liga a identidade nova (ou grava a senha nova) depois disto.
 */
@Component
public class UnprovenAccess {

    private final JdbcClient jdbc;
    private final AuditService audit;

    public UnprovenAccess(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** Só para conta com e-mail ainda não confirmado; quem chama confere isso. */
    public void revoke(User user) {
        boolean hadPassword = user.getPasswordHash() != null;
        if (hadPassword) {
            user.changePassword(null);
        }
        jdbc.sql("DELETE FROM sessions WHERE user_id = :user").param("user", user.getId()).update();
        // Inclui a conta do Google ou Discord do próprio atacante, ligada antes de ele trocar o e-mail
        int providers = jdbc.sql("DELETE FROM oauth_accounts WHERE user_id = :user").param("user", user.getId()).update();
        jdbc.sql("UPDATE email_tokens SET used_at = now() WHERE user_id = :user AND used_at IS NULL")
                .param("user", user.getId()).update();
        int twoFactor = jdbc.sql("""
                UPDATE users SET totp_secret_encrypted = NULL, two_factor_enabled_at = NULL, totp_last_step = NULL
                WHERE id = :user AND totp_secret_encrypted IS NOT NULL
                """).param("user", user.getId()).update();
        if (twoFactor > 0) {
            jdbc.sql("DELETE FROM two_factor_recovery_codes WHERE user_id = :user").param("user", user.getId()).update();
            jdbc.sql("DELETE FROM user_badges WHERE user_id = :user AND badge_key = 'protected'").param("user", user.getId()).update();
            jdbc.sql("SELECT refresh_user_progress(:user)").param("user", user.getId()).query().listOfRows();
        }
        audit.record(user.getId(), "account.email_proven", null, null, Map.of(
                "passwordRemoved", hadPassword, "twoFactorRemoved", twoFactor > 0, "providersRemoved", providers));
    }
}
