-- Contorno do avatar que a pessoa pode usar de verdade: o de fundador, se tiver número, ou um rank que já
-- alcançou. Antes estava copiado em cada consulta que mostra autor.
CREATE FUNCTION gh_ring_style(ring_style text, founder_number integer, level integer) RETURNS text
LANGUAGE sql STABLE PARALLEL SAFE AS $$
    SELECT CASE
        WHEN ring_style = 'founder' AND founder_number IS NOT NULL THEN 'founder'
        WHEN EXISTS (SELECT 1 FROM ranks rk WHERE rk.name = ring_style AND rk.min_level <= level) THEN ring_style
    END
$$;

-- Busca de pessoas por trecho do nome com índice, em vez de ler a tabela inteira a cada letra digitada
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE FUNCTION gh_person_text(username text, display_name text) RETURNS text
LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT gh_unaccent(coalesce(username, '') || ' ' || coalesce(display_name, ''))
$$;

-- O feed agora pagina por data e id (duas publicações no mesmo instante não são mais puladas).
-- As reviews já tinham esse índice; os posts só tinham a data
DROP INDEX idx_posts_feed;
CREATE INDEX idx_posts_feed ON posts (created_at DESC, id DESC) WHERE deleted_at IS NULL;

CREATE INDEX idx_users_search ON users USING gin (gh_person_text(username, display_name) gin_trgm_ops)
    WHERE deleted_at IS NULL;
