-- Denúncias de posts, reviews, comentários e perfis. Por enquanto só registram: a moderação decide depois
CREATE TABLE reports (
    id            bigserial PRIMARY KEY,
    reporter_id   uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    target_kind   text NOT NULL CHECK (target_kind IN ('post', 'review', 'comment', 'user')),
    target_id     uuid NOT NULL,
    reason        text NOT NULL CHECK (reason IN ('spam', 'harassment', 'hate', 'inappropriate', 'scam', 'misinformation', 'impersonation', 'other')),
    details       text CHECK (char_length(details) <= 500),
    status        text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'dismissed', 'resolved')),
    created_at    timestamptz NOT NULL DEFAULT now(),
    reviewed_by   uuid REFERENCES users (id) ON DELETE SET NULL,
    reviewed_at   timestamptz,
    review_note   text CHECK (char_length(review_note) <= 500),
    -- Cada pessoa denuncia o mesmo conteúdo uma vez
    UNIQUE (reporter_id, target_kind, target_id)
);

CREATE INDEX idx_reports_target ON reports (target_kind, target_id);
CREATE INDEX idx_reports_pending ON reports (created_at) WHERE status = 'pending';

-- Nada é apagado de verdade: passado o prazo para desistir, a conta fica encerrada em definitivo
ALTER TABLE users ADD COLUMN closed_at timestamptz;

-- Auditoria: o que cada pessoa fez no site, para o painel de admin consultar o histórico de um perfil
CREATE TABLE audit_events (
    id          bigserial PRIMARY KEY,
    actor_id    uuid REFERENCES users (id) ON DELETE SET NULL,
    action      text NOT NULL,
    target_kind text,
    target_id   uuid,
    data        jsonb NOT NULL DEFAULT '{}',
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_actor ON audit_events (actor_id, created_at DESC);
CREATE INDEX idx_audit_target ON audit_events (target_kind, target_id);
