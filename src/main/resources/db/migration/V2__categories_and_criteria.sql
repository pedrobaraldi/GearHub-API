-- Dados de referência: necessários em todos os ambientes

INSERT INTO categories (slug, name, position) VALUES
    ('cpu', 'CPU', 1),
    ('gpu', 'GPU', 2),
    ('headset', 'Headset', 3),
    ('microfone', 'Microfone', 4),
    ('monitor', 'Monitor', 5),
    ('mouse', 'Mouse', 6),
    ('mousepad', 'Mousepad', 7),
    ('notebook', 'Notebook', 8),
    ('ssd', 'SSD', 9),
    ('teclado', 'Teclado', 10),
    ('webcam', 'Webcam', 11);

INSERT INTO criteria (category_id, label, position)
SELECT c.id, v.label, v.position
FROM (VALUES
    ('mouse', 'Construção', 1), ('mouse', 'Ergonomia', 2), ('mouse', 'Sensor', 3), ('mouse', 'Botões', 4),
    ('mousepad', 'Deslize', 1), ('mousepad', 'Controle', 2), ('mousepad', 'Textura', 3), ('mousepad', 'Base', 4),
    ('teclado', 'Switches', 1), ('teclado', 'Build Quality', 2), ('teclado', 'Software', 3), ('teclado', 'Latência', 4),
    ('headset', 'Áudio', 1), ('headset', 'Microfone', 2), ('headset', 'Build Quality', 3), ('headset', 'Conforto', 4),
    ('cpu', 'Desempenho', 1), ('cpu', 'Temperatura', 2), ('cpu', 'Consumo', 3), ('cpu', 'Custo-benefício', 4),
    ('gpu', 'Desempenho', 1), ('gpu', 'Temperatura', 2), ('gpu', 'Ruído', 3), ('gpu', 'Custo-benefício', 4),
    ('microfone', 'Qualidade de voz', 1), ('microfone', 'Isolamento', 2), ('microfone', 'Construção', 3), ('microfone', 'Custo-benefício', 4),
    ('monitor', 'Imagem', 1), ('monitor', 'Fluidez', 2), ('monitor', 'Construção', 3), ('monitor', 'Custo-benefício', 4),
    ('notebook', 'Desempenho', 1), ('notebook', 'Tela', 2), ('notebook', 'Bateria', 3), ('notebook', 'Construção', 4),
    ('ssd', 'Velocidade', 1), ('ssd', 'Durabilidade', 2), ('ssd', 'Temperatura', 3), ('ssd', 'Custo-benefício', 4),
    ('webcam', 'Imagem', 1), ('webcam', 'Áudio', 2), ('webcam', 'Software', 3), ('webcam', 'Custo-benefício', 4)
) AS v (slug, label, position)
JOIN categories c ON c.slug = v.slug;
