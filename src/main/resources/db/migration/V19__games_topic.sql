-- Tópico para falar de jogos: base da insígnia Voz dos Games. Entra logo depois de Promoção.
UPDATE post_topics SET position = position + 1 WHERE position >= 5;

INSERT INTO post_topics (slug, name, emoji, position) VALUES ('games', 'Games', '🕹️', 5);
