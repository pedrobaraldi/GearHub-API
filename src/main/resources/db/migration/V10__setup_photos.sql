-- Fotos do setup exibidas no perfil, na ordem escolhida pela pessoa
ALTER TABLE users ADD COLUMN setup_photos jsonb NOT NULL DEFAULT '[]';
