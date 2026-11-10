-- Verificação em duas etapas com app autenticador (TOTP, RFC 6238)

-- Segredo criptografado com AES. Sem data de ativação, a configuração começou e ainda não foi confirmada
ALTER TABLE users ADD COLUMN totp_secret_encrypted text;
ALTER TABLE users ADD COLUMN two_factor_enabled_at timestamptz;
-- Último intervalo de 30 s aceito: o mesmo código não entra duas vezes
ALTER TABLE users ADD COLUMN totp_last_step bigint;

-- Códigos de uso único para quem perdeu o celular. Só o HMAC fica no banco
CREATE TABLE two_factor_recovery_codes (
    id         bigserial PRIMARY KEY,
    user_id    uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    code_hash  text NOT NULL,
    used_at    timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (user_id, code_hash)
);

-- Senha certa, falta o código: o login fica em espera, sem sessão, por poucos minutos
CREATE TABLE two_factor_challenges (
    token_hash text PRIMARY KEY,
    user_id    uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    attempts   int NOT NULL DEFAULT 0,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_two_factor_challenges_expires ON two_factor_challenges (expires_at);
