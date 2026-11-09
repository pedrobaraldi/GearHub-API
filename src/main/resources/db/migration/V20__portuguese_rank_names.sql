-- Patentes com nomes de materiais, do Cobre ao Diamante (mesmos níveis)
UPDATE ranks SET name = CASE name
    WHEN 'Bare Metal' THEN 'Cobre'
    WHEN 'Silicon Crafter' THEN 'Prata'
    WHEN 'Circuit Runner' THEN 'Cobalto'
    WHEN 'Clockmaster' THEN 'Esmeralda'
    WHEN 'Liquid Cooled' THEN 'Platina'
    WHEN 'Overclocked Prime' THEN 'Diamante'
    ELSE name
END;

UPDATE users SET rank_name = CASE rank_name
    WHEN 'Bare Metal' THEN 'Cobre'
    WHEN 'Silicon Crafter' THEN 'Prata'
    WHEN 'Circuit Runner' THEN 'Cobalto'
    WHEN 'Clockmaster' THEN 'Esmeralda'
    WHEN 'Liquid Cooled' THEN 'Platina'
    WHEN 'Overclocked Prime' THEN 'Diamante'
    ELSE rank_name
END;

ALTER TABLE users ALTER COLUMN rank_name SET DEFAULT 'Cobre';
