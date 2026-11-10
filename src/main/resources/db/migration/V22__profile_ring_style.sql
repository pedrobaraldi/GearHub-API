-- Contorno da foto escolhido pela pessoa: o nome de um rank já alcançado ou 'founder'.
-- Vazio é automático (cor do rank atual, ou RGB para fundador)
ALTER TABLE users ADD COLUMN ring_style text CHECK (length(ring_style) <= 40);
