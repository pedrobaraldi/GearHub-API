-- Troca de e-mail só vale depois de confirmada por um link enviado ao endereço novo.
-- Até lá, o e-mail novo fica guardado no próprio token (criptografado, com o HMAC para a busca)
ALTER TABLE email_tokens DROP CONSTRAINT email_tokens_purpose_check;
ALTER TABLE email_tokens ADD CONSTRAINT email_tokens_purpose_check
    CHECK (purpose IN ('verify_email', 'reset_password', 'change_email'));
ALTER TABLE email_tokens ADD COLUMN new_email_encrypted text;
ALTER TABLE email_tokens ADD COLUMN new_email_hash text;
ALTER TABLE email_tokens ADD CONSTRAINT email_tokens_change_has_email
    CHECK (purpose <> 'change_email' OR (new_email_encrypted IS NOT NULL AND new_email_hash IS NOT NULL));
