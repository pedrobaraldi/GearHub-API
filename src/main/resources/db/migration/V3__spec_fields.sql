-- Ficha técnica: cada categoria define os campos do formulário de post,
-- e o post guarda os valores preenchidos.

CREATE TABLE spec_fields (
    id          integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    category_id integer NOT NULL REFERENCES categories (id) ON DELETE CASCADE,
    key         text NOT NULL CHECK (key ~ '^[a-z0-9_]+$'),
    label       text NOT NULL,
    required    boolean NOT NULL DEFAULT false,
    position    integer NOT NULL DEFAULT 0,
    UNIQUE (category_id, key)
);

-- Valores preenchidos pelos usuários, no formato {"chave_do_campo": "texto"}
ALTER TABLE reviews ADD COLUMN specs jsonb NOT NULL DEFAULT '{}';

INSERT INTO spec_fields (category_id, key, label, position)
SELECT c.id, v.key, v.label, v.position
FROM (VALUES
    ('mouse', 'modelo', 'Modelo', 0),
    ('mouse', 'dimensoes', 'Dimensões', 1),
    ('mouse', 'formato', 'Formato', 2),
    ('mouse', 'pegadas', 'Pegadas', 3),
    ('mouse', 'peso', 'Peso', 4),
    ('mouse', 'botoes', 'Botões', 5),
    ('mouse', 'switches_principais', 'Switches principais', 6),
    ('mouse', 'scroll_encoder', 'Scroll/Encoder', 7),
    ('mouse', 'sensor', 'Sensor', 8),
    ('mouse', 'dpi', 'DPI', 9),
    ('mouse', 'ips', 'IPS', 10),
    ('mouse', 'aceleracao', 'Aceleração', 11),
    ('mouse', 'polling_rate', 'Polling rate', 12),
    ('mouse', 'conexao', 'Conexão', 13),
    ('mouse', 'duracao_bateria', 'Duração da bateria', 14),
    ('mouse', 'feet', 'Feet', 15),
    ('mouse', 'software', 'Software', 16),

    ('teclado', 'modelo', 'Modelo', 0),
    ('teclado', 'layout', 'Layout', 1),
    ('teclado', 'switches', 'Switches', 2),
    ('teclado', 'tipo_switch', 'Tipo de switch', 3),
    ('teclado', 'forca_acionamento', 'Força de acionamento', 4),
    ('teclado', 'ponto_acionamento', 'Ponto de acionamento', 5),
    ('teclado', 'curso_total', 'Curso total', 6),
    ('teclado', 'rapid_trigger', 'Rapid Trigger', 7),
    ('teclado', 'polling_rate', 'Polling rate', 8),
    ('teclado', 'keycaps', 'Keycaps', 9),
    ('teclado', 'carcaca', 'Carcaça', 10),
    ('teclado', 'hot_swap', 'Hot swap', 11),
    ('teclado', 'conexao', 'Conexão', 12),
    ('teclado', 'iluminacao', 'Iluminação', 13),
    ('teclado', 'anti_ghosting', 'Anti-ghosting', 14),
    ('teclado', 'dimensoes', 'Dimensões', 15),
    ('teclado', 'peso', 'Peso', 16),
    ('teclado', 'cores', 'Cores', 17),
    ('teclado', 'software', 'Software', 18),
    ('teclado', 'compatibilidade', 'Compatibilidade', 19),
    ('teclado', 'vida_util', 'Vida útil', 20),

    ('mousepad', 'modelo', 'Modelo', 1),
    ('mousepad', 'superficie', 'Superfície', 2),
    ('mousepad', 'deslize', 'Deslize', 3),
    ('mousepad', 'velocidade', 'Velocidade', 4),
    ('mousepad', 'poder_parada', 'Poder de parada', 5),
    ('mousepad', 'base', 'Base', 6),
    ('mousepad', 'bordas', 'Bordas', 7),
    ('mousepad', 'dimensoes', 'Dimensões', 8),
    ('mousepad', 'espessura', 'Espessura', 9)
) AS v (slug, key, label, position)
JOIN categories c ON c.slug = v.slug;

-- Campos essenciais: sem eles o post não pode ser publicado
UPDATE spec_fields f
SET required = true
FROM categories c
WHERE c.id = f.category_id
  AND (c.slug, f.key) IN (
    ('mouse', 'modelo'), ('mouse', 'formato'), ('mouse', 'peso'), ('mouse', 'sensor'), ('mouse', 'conexao'),
    ('teclado', 'modelo'), ('teclado', 'layout'), ('teclado', 'switches'), ('teclado', 'tipo_switch'), ('teclado', 'conexao'),
    ('mousepad', 'modelo'), ('mousepad', 'superficie'), ('mousepad', 'velocidade'), ('mousepad', 'dimensoes')
  );
