-- Eventos de progressão que o site anima uma vez: subida de rank ou desbloqueio do Fundador
CREATE TABLE rank_ups (
    id         bigserial PRIMARY KEY,
    user_id    uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    kind       text NOT NULL DEFAULT 'rank' CHECK (kind IN ('rank', 'founder')),
    from_rank  text,
    to_rank    text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    seen_at    timestamptz
);

CREATE INDEX rank_ups_pending ON rank_ups (user_id) WHERE seen_at IS NULL;

-- Fundador: os 100 primeiros cadastros. Cada um guarda o número da ordem em que entrou (1 a 100)
ALTER TABLE users ADD COLUMN founder_number integer UNIQUE CHECK (founder_number BETWEEN 1 AND 100);

UPDATE users u SET founder_number = r.n
FROM (SELECT id, row_number() OVER (ORDER BY created_at, id) AS n FROM users) r
WHERE u.id = r.id AND r.n <= 100;

INSERT INTO badges (key, name, description, position) VALUES
    ('founder', 'Fundador', '100 primeiros usuários', 0);

INSERT INTO user_badges (user_id, badge_key)
SELECT id, 'founder' FROM users WHERE founder_number IS NOT NULL
ON CONFLICT DO NOTHING;

-- Os fundadores que já existem também veem a animação de desbloqueio uma vez
INSERT INTO rank_ups (user_id, kind, to_rank)
SELECT id, 'founder', 'Fundador' FROM users WHERE founder_number IS NOT NULL;

-- A trava evita que dois cadastros ao mesmo tempo peguem o mesmo número
CREATE FUNCTION grant_founder_badge() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    next_number integer;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtext('founder_number'));
    SELECT count(*) INTO next_number FROM users;
    IF next_number <= 100 THEN
        UPDATE users SET founder_number = next_number WHERE id = NEW.id;
        INSERT INTO user_badges (user_id, badge_key) VALUES (NEW.id, 'founder') ON CONFLICT DO NOTHING;
        INSERT INTO rank_ups (user_id, kind, to_rank) VALUES (NEW.id, 'founder', 'Fundador');
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER users_founder_badge AFTER INSERT ON users
    FOR EACH ROW EXECUTE FUNCTION grant_founder_badge();


-- Só conta subida entre ranks que existem: renomear um rank (como na V20) não gera registro
CREATE FUNCTION record_rank_up() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    old_min integer;
    new_min integer;
BEGIN
    SELECT min_level INTO old_min FROM ranks WHERE name = OLD.rank_name;
    SELECT min_level INTO new_min FROM ranks WHERE name = NEW.rank_name;
    IF old_min IS NOT NULL AND new_min IS NOT NULL AND new_min > old_min THEN
        INSERT INTO rank_ups (user_id, from_rank, to_rank) VALUES (NEW.id, OLD.rank_name, NEW.rank_name);
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER users_rank_up AFTER UPDATE OF rank_name ON users
    FOR EACH ROW WHEN (OLD.rank_name IS DISTINCT FROM NEW.rank_name)
    EXECUTE FUNCTION record_rank_up();
