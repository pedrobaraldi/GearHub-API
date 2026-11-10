-- Ficha dos jogos vinda do IGDB, guardada aqui: o IGDB pede cache local, e a página não depende dele estar no ar
CREATE TABLE catalog_games (
    igdb_id            integer PRIMARY KEY,
    slug               text NOT NULL UNIQUE,
    name               text NOT NULL,
    cover_url          text,
    first_release_date date,
    total_rating       numeric(5, 2),
    steam_appid        integer,
    -- Ficha completa já no formato da resposta da API
    details            jsonb NOT NULL,
    synced_at          timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_catalog_games_steam ON catalog_games (steam_appid);

-- Jogo acompanhado na Steam ligado à sua página no catálogo
ALTER TABLE market_games ADD COLUMN igdb_slug text;
