-- Busca sem acento e sem maiúscula: "memoria" acha "Memória". Sem depender de extensão do Postgres
CREATE FUNCTION gh_unaccent(value text) RETURNS text
LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT translate(lower(coalesce(value, '')),
                     'áàâãäåéèêëíìîïóòôõöúùûüçñý',
                     'aaaaaaeeeeiiiiooooouuuucny')
$$;
