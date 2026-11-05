-- Posts passam a ser como no formulário da comunidade: texto, tópico, tags e imagem.
-- O título fica nulo nos novos; posts antigos mantêm o que já tinham.

CREATE TABLE post_topics (
    id       serial PRIMARY KEY,
    slug     text NOT NULL UNIQUE,
    name     text NOT NULL UNIQUE,
    emoji    text NOT NULL,
    position integer NOT NULL
);

INSERT INTO post_topics (slug, name, emoji, position) VALUES
    ('discussao', 'Discussão', '💬', 1),
    ('duvida', 'Dúvida', '❓', 2),
    ('recomendacao', 'Recomendação', '💡', 3),
    ('promocao', 'Promoção', '🏷️', 4),
    ('setup', 'Setup', '🖥️', 5),
    ('hardware', 'Hardware', '⚙️', 6),
    ('mouse', 'Mouse', '🖱️', 7),
    ('teclado', 'Teclado', '⌨️', 8),
    ('mousepad', 'Mousepad', '🟦', 9),
    ('headset', 'Headset', '🎧', 10),
    ('gpu', 'GPU', '🎮', 11),
    ('cpu', 'CPU', '🧠', 12),
    ('monitor', 'Monitor', '🖥️', 13),
    ('noticias', 'Notícias', '📰', 14),
    ('off-topic', 'Off Topic', '☕', 15);

ALTER TABLE posts
    ADD COLUMN topic_id integer REFERENCES post_topics (id),
    ADD COLUMN tags jsonb NOT NULL DEFAULT '[]',
    ALTER COLUMN title DROP NOT NULL;

UPDATE posts p
SET topic_id = COALESCE(
    (SELECT t.id FROM post_topics t JOIN categories c ON c.slug = t.slug WHERE c.id = p.category_id),
    (SELECT id FROM post_topics WHERE slug = 'hardware'))
WHERE p.category_id IS NOT NULL;

ALTER TABLE posts DROP COLUMN category_id;
