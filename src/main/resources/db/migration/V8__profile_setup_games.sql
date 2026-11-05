-- Galeria de até 4 imagens em posts e reviews
ALTER TABLE posts ADD COLUMN images jsonb NOT NULL DEFAULT '[]';
ALTER TABLE reviews ADD COLUMN images jsonb NOT NULL DEFAULT '[]';

-- Perfil: banner, redes e setup
ALTER TABLE users
    ADD COLUMN banner_url text,
    ADD COLUMN website text,
    ADD COLUMN twitter text,
    ADD COLUMN instagram text,
    ADD COLUMN youtube text,
    ADD COLUMN setup jsonb NOT NULL DEFAULT '{}';

CREATE TABLE setup_slots (
    id       serial PRIMARY KEY,
    key      text NOT NULL UNIQUE CHECK (key ~ '^[a-z0-9_]+$'),
    label    text NOT NULL,
    section  text NOT NULL CHECK (section IN ('perifericos', 'hardware')),
    position integer NOT NULL
);

INSERT INTO setup_slots (key, label, section, position) VALUES
    ('mouse', 'Mouse', 'perifericos', 1),
    ('teclado', 'Teclado', 'perifericos', 2),
    ('mousepad', 'Mousepad', 'perifericos', 3),
    ('headset', 'Headset', 'perifericos', 4),
    ('microfone', 'Microfone', 'perifericos', 5),
    ('webcam', 'Webcam', 'perifericos', 6),
    ('monitor', 'Monitor', 'perifericos', 7),
    ('cpu', 'CPU', 'hardware', 8),
    ('gpu', 'GPU', 'hardware', 9),
    ('placa_mae', 'Placa-mãe', 'hardware', 10),
    ('ram', 'RAM', 'hardware', 11),
    ('ssd', 'SSD', 'hardware', 12),
    ('fonte', 'Fonte', 'hardware', 13),
    ('gabinete', 'Gabinete', 'hardware', 14);

-- Jogos favoritos: lista fixa, com os mesmos campos para todos
CREATE TABLE games (
    id       serial PRIMARY KEY,
    slug     text NOT NULL UNIQUE,
    name     text NOT NULL UNIQUE,
    position integer NOT NULL
);

INSERT INTO games (slug, name, position) VALUES
    ('valorant', 'Valorant', 1),
    ('cs2', 'Counter-Strike 2', 2),
    ('fortnite', 'Fortnite', 3),
    ('apex-legends', 'Apex Legends', 4),
    ('league-of-legends', 'League of Legends', 5),
    ('overwatch-2', 'Overwatch 2', 6),
    ('rainbow-six-siege', 'Rainbow Six Siege', 7),
    ('call-of-duty', 'Call of Duty', 8),
    ('rocket-league', 'Rocket League', 9),
    ('dota-2', 'Dota 2', 10);

CREATE TABLE user_games (
    user_id      uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    game_id      integer NOT NULL REFERENCES games (id),
    dpi          integer CHECK (dpi BETWEEN 50 AND 100000),
    sensitivity  numeric(8, 3) CHECK (sensitivity BETWEEN 0 AND 1000),
    resolution   text CHECK (char_length(resolution) <= 20),
    aspect_ratio text CHECK (char_length(aspect_ratio) <= 10),
    refresh_rate integer CHECK (refresh_rate BETWEEN 1 AND 1000),
    created_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, game_id)
);
