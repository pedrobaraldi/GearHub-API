-- Respostas têm um nível: responder a uma resposta fica na mesma conversa e cita a pessoa
ALTER TABLE comments
    ADD COLUMN parent_id uuid REFERENCES comments (id) ON DELETE CASCADE,
    ADD COLUMN reply_to_user_id uuid REFERENCES users (id) ON DELETE SET NULL;

CREATE INDEX idx_comments_parent ON comments (parent_id) WHERE deleted_at IS NULL;
