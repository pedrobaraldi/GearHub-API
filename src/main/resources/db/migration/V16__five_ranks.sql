-- Cinco patentes, da chegada à referência da comunidade
UPDATE ranks SET min_level = 20 WHERE name = 'Especialista';

INSERT INTO ranks (name, min_level) VALUES
    ('Analista', 5),
    ('Lenda', 35);

SELECT refresh_user_progress(id) FROM users;
