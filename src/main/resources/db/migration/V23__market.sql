-- Aba Games: jogos da Steam acompanhados, com preço e jogadores coletados de hora em hora.
-- A Steam só informa o valor de agora; o histórico nasce da nossa coleta.
CREATE TABLE market_games (
    steam_appid        integer PRIMARY KEY,
    name               text NOT NULL,
    header_image       text,
    short_description  text,
    developers         text,
    genres             text[] NOT NULL DEFAULT '{}',
    release_date       text,
    is_free            boolean NOT NULL DEFAULT false,
    currency           text,
    initial_cents      integer,
    final_cents        integer,
    discount_percent   integer NOT NULL DEFAULT 0,
    current_players    integer,
    review_positive    integer,
    review_negative    integer,
    review_summary     text,
    tracked_since      timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz
);

-- Uma linha por mudança de preço (preço quase nunca muda de hora em hora)
CREATE TABLE market_price_snapshots (
    steam_appid      integer NOT NULL REFERENCES market_games ON DELETE CASCADE,
    taken_at         timestamptz NOT NULL DEFAULT now(),
    currency         text NOT NULL,
    initial_cents    integer NOT NULL,
    final_cents      integer NOT NULL,
    discount_percent integer NOT NULL,
    PRIMARY KEY (steam_appid, taken_at)
);

-- Jogadores online, uma linha por coleta
CREATE TABLE market_player_snapshots (
    steam_appid integer NOT NULL REFERENCES market_games ON DELETE CASCADE,
    taken_at    timestamptz NOT NULL DEFAULT now(),
    players     integer NOT NULL,
    PRIMARY KEY (steam_appid, taken_at)
);

-- Lista inicial: jogos populares da Steam. O nome é provisório até a primeira coleta
INSERT INTO market_games (steam_appid, name) VALUES
    (730, 'Counter-Strike 2'),
    (570, 'Dota 2'),
    (578080, 'PUBG: BATTLEGROUNDS'),
    (1172470, 'Apex Legends'),
    (252490, 'Rust'),
    (271590, 'Grand Theft Auto V'),
    (1086940, 'Baldur''s Gate 3'),
    (1091500, 'Cyberpunk 2077'),
    (1245620, 'ELDEN RING'),
    (2767030, 'Marvel Rivals'),
    (553850, 'HELLDIVERS 2'),
    (359550, 'Tom Clancy''s Rainbow Six Siege'),
    (105600, 'Terraria'),
    (413150, 'Stardew Valley'),
    (1938090, 'Call of Duty'),
    (230410, 'Warframe'),
    (440, 'Team Fortress 2'),
    (1623730, 'Palworld'),
    (2357570, 'Overwatch 2'),
    (892970, 'Valheim');
