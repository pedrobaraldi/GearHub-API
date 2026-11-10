-- Post sobre um jogo: liga à ficha do catálogo, de onde vêm nome e capa sempre atualizados
ALTER TABLE posts ADD COLUMN game_id integer REFERENCES catalog_games (igdb_id) ON DELETE SET NULL;

CREATE INDEX idx_posts_game ON posts (game_id) WHERE game_id IS NOT NULL;
