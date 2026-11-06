-- Categorias do mesmo grupo aparecem juntas nas reviews relacionadas de um produto
ALTER TABLE categories ADD COLUMN related_group text;

UPDATE categories c SET related_group = v.related_group
FROM (VALUES
    ('mouse', 'mira'),
    ('mousepad', 'mira'),
    ('teclado', 'teclado'),
    ('headset', 'audio'),
    ('fone-intra', 'audio'),
    ('microfone', 'audio'),
    ('monitor', 'imagem'),
    ('webcam', 'imagem'),
    ('cpu', 'pc'),
    ('gpu', 'pc'),
    ('placa-mae', 'pc'),
    ('ram', 'pc'),
    ('ssd', 'pc'),
    ('fonte', 'pc'),
    ('gabinete', 'pc'),
    ('air-cooler', 'pc'),
    ('water-cooler', 'pc'),
    ('notebook', 'notebook')
) AS v (slug, related_group)
WHERE c.slug = v.slug;

ALTER TABLE categories ALTER COLUMN related_group SET NOT NULL;
