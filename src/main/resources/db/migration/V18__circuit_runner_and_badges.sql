-- Seis patentes: Circuit Runner entra no tier 3 e as de cima sobem (de cima para baixo, por causa do UNIQUE)
UPDATE ranks SET min_level = 40 WHERE name = 'Overclocked Prime';
UPDATE ranks SET min_level = 30 WHERE name = 'Liquid Cooled';
UPDATE ranks SET min_level = 20 WHERE name = 'Clockmaster';
INSERT INTO ranks (name, min_level) VALUES ('Circuit Runner', 10);

SELECT refresh_user_progress(id) FROM users;

-- Insígnias exclusivas: dadas por um admin para quem se destaca em algo
CREATE TABLE badges (
    key         text PRIMARY KEY,
    name        text NOT NULL,
    description text NOT NULL,
    position    integer NOT NULL UNIQUE
);

INSERT INTO badges (key, name, description, position) VALUES
    ('developer', 'Desenvolvedor', 'Equipe do projeto', 1),
    ('partner', 'Parceiro', 'Influencers e parceiros', 2),
    ('pro-player', 'Pro Player', 'Jogadores profissionais', 3),
    ('reviewer', 'Reviewer', 'Muitas reviews', 4),
    ('game-talker', 'Voz dos Games', 'Fala muito de games', 5);

CREATE TABLE user_badges (
    user_id    uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    badge_key  text NOT NULL REFERENCES badges (key) ON DELETE CASCADE,
    granted_by uuid REFERENCES users (id) ON DELETE SET NULL,
    granted_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, badge_key)
);
