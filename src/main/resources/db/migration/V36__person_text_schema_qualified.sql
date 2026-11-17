-- gh_person_text é usada no índice idx_users_search. A análise automática do Postgres (e uma restauração com
-- pg_dump) roda com search_path restrito e não achava gh_unaccent: a análise da tabela users falhava a cada
-- rodada. Com o schema explícito, a função funciona em qualquer search_path. O resultado é o mesmo.
CREATE OR REPLACE FUNCTION gh_person_text(username text, display_name text) RETURNS text
LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT public.gh_unaccent(coalesce(username, '') || ' ' || coalesce(display_name, ''))
$$;

REINDEX INDEX idx_users_search;
