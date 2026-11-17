-- Login do painel de admin (programa de desktop): token de acesso curto, só para admin com 2FA.
-- Só o hash do token fica no banco; o aparelho e o IP ficam criptografados. Revogar não apaga a linha
CREATE TABLE admin_app_tokens (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             uuid NOT NULL REFERENCES users (id),
    token_hash          text NOT NULL UNIQUE,
    device_name         text NOT NULL CHECK (length(device_name) BETWEEN 1 AND 80),
    ip_encrypted        text,
    created_at          timestamptz NOT NULL DEFAULT now(),
    expires_at          timestamptz NOT NULL,
    last_used_at        timestamptz,
    revoked_at          timestamptz,
    revoked_by          uuid REFERENCES users (id)
);
CREATE INDEX idx_admin_app_tokens_user ON admin_app_tokens (user_id, created_at DESC);
