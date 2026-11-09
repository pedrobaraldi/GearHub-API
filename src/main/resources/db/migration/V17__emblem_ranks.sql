-- Patentes com os emblemas definitivos
UPDATE ranks SET name = CASE name
    WHEN 'Explorador' THEN 'Bare Metal'
    WHEN 'Analista' THEN 'Silicon Crafter'
    WHEN 'Reviewer' THEN 'Clockmaster'
    WHEN 'Especialista' THEN 'Liquid Cooled'
    WHEN 'Lenda' THEN 'Overclocked Prime'
    ELSE name
END;

ALTER TABLE users ALTER COLUMN rank_name SET DEFAULT 'Bare Metal';

SELECT refresh_user_progress(id) FROM users;
