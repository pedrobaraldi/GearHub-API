-- Review de jogo: categoria própria, com notas pensadas para jogos e sem ficha técnica
INSERT INTO categories (slug, name, position, related_group)
SELECT 'jogos', 'Jogos', coalesce(max(position), 0) + 1, 'jogos' FROM categories;

INSERT INTO criteria (category_id, label, position)
SELECT c.id, v.label, v.position
FROM categories c, (VALUES ('Gráficos', 1), ('Jogabilidade', 2), ('História', 3), ('Desempenho', 4), ('Diversão', 5)) AS v (label, position)
WHERE c.slug = 'jogos';

-- O "produto" de uma review de jogo é o próprio jogo do catálogo: um por jogo
ALTER TABLE products ADD COLUMN game_id integer UNIQUE REFERENCES catalog_games (igdb_id) ON DELETE SET NULL;
