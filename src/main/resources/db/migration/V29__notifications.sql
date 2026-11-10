-- Notificações no site: curtidas, comentários, novos seguidores e subida de rank

CREATE TABLE notifications (
    id          bigserial PRIMARY KEY,
    user_id     uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    actor_id    uuid REFERENCES users (id) ON DELETE CASCADE,
    type        text NOT NULL CHECK (type IN ('like', 'comment', 'reply', 'follow', 'rank_up')),
    -- O que recebeu a ação: post, review ou comentário. Vazio em seguidor e subida de rank
    target_kind text CHECK (target_kind IN ('post', 'review', 'comment')),
    target_id   uuid,
    data        jsonb NOT NULL DEFAULT '{}',
    created_at  timestamptz NOT NULL DEFAULT now(),
    read_at     timestamptz
);

CREATE INDEX idx_notifications_user ON notifications (user_id, created_at DESC);
CREATE INDEX idx_notifications_unread ON notifications (user_id) WHERE read_at IS NULL;

-- Curtir, descurtir e curtir de novo não repete a notificação; seguir também não
CREATE UNIQUE INDEX uq_notifications_once ON notifications
    (user_id, actor_id, type, coalesce(target_kind, ''), coalesce(target_id, '00000000-0000-0000-0000-000000000000'::uuid))
    WHERE type IN ('like', 'follow');

-- O que cada pessoa quer receber. Sem linha, recebe tudo
CREATE TABLE notification_settings (
    user_id    uuid PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    likes      boolean NOT NULL DEFAULT true,
    comments   boolean NOT NULL DEFAULT true,
    follows    boolean NOT NULL DEFAULT true,
    rank_ups   boolean NOT NULL DEFAULT true,
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- Subida de rank (e o Fundador) já vira registro em rank_ups: daqui sai a notificação
CREATE FUNCTION rank_up_notification_trigger() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF coalesce((SELECT rank_ups FROM notification_settings WHERE user_id = NEW.user_id), true) THEN
        INSERT INTO notifications (user_id, type, data)
        VALUES (NEW.user_id, 'rank_up', jsonb_build_object('rank', NEW.to_rank, 'kind', NEW.kind));
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_rank_up_notification AFTER INSERT ON rank_ups
    FOR EACH ROW EXECUTE FUNCTION rank_up_notification_trigger();
