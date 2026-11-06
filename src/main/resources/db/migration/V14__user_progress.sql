-- XP calculada só com atividade real. As regras ficam em tabelas, e a tela lê as mesmas regras.

ALTER TABLE users ADD COLUMN xp integer NOT NULL DEFAULT 0 CHECK (xp >= 0);

CREATE TABLE xp_rules (
    key      text PRIMARY KEY,
    label    text NOT NULL,
    points   integer NOT NULL CHECK (points > 0),
    position integer NOT NULL
);

INSERT INTO xp_rules (key, label, points, position) VALUES
    ('review', 'Review publicada', 50, 1),
    ('post', 'Post publicado', 10, 2),
    ('comment', 'Comentário escrito', 5, 3),
    ('like', 'Curtida recebida', 3, 4),
    ('follower', 'Seguidor', 5, 5);

CREATE TABLE ranks (
    name      text PRIMARY KEY,
    min_level integer NOT NULL UNIQUE CHECK (min_level >= 1)
);

INSERT INTO ranks (name, min_level) VALUES
    ('Explorador', 1),
    ('Reviewer', 10),
    ('Especialista', 25);

-- Curtir o próprio conteúdo não conta
CREATE FUNCTION user_xp_counts(target uuid) RETURNS TABLE (key text, total bigint)
LANGUAGE sql STABLE AS $$
    SELECT 'review', count(*) FROM reviews WHERE user_id = target AND deleted_at IS NULL
    UNION ALL
    SELECT 'post', count(*) FROM posts WHERE user_id = target AND deleted_at IS NULL
    UNION ALL
    SELECT 'comment', count(*) FROM comments WHERE user_id = target AND deleted_at IS NULL
    UNION ALL
    SELECT 'like', (
        SELECT count(*) FROM review_likes l JOIN reviews r ON r.id = l.review_id
        WHERE r.user_id = target AND r.deleted_at IS NULL AND l.user_id <> target
    ) + (
        SELECT count(*) FROM post_likes l JOIN posts p ON p.id = l.post_id
        WHERE p.user_id = target AND p.deleted_at IS NULL AND l.user_id <> target
    ) + (
        SELECT count(*) FROM comment_likes l JOIN comments c ON c.id = l.comment_id
        WHERE c.user_id = target AND c.deleted_at IS NULL AND l.user_id <> target
    )
    UNION ALL
    SELECT 'follower', count(*) FROM follows WHERE followed_id = target
$$;

-- XP total para chegar ao nível n: 2 = 100, 3 = 300, 4 = 600, 5 = 1000...
CREATE FUNCTION xp_for_level(n integer) RETURNS integer
LANGUAGE sql IMMUTABLE AS $$ SELECT 50 * n * (n - 1) $$;

CREATE FUNCTION level_for_xp(xp integer) RETURNS integer
LANGUAGE sql IMMUTABLE AS $$ SELECT floor((1 + sqrt(1 + xp / 12.5)) / 2)::integer $$;

CREATE FUNCTION refresh_user_progress(target uuid) RETURNS void
LANGUAGE plpgsql AS $$
DECLARE
    new_xp integer;
    new_level integer;
BEGIN
    SELECT coalesce(sum(c.total * r.points), 0)::integer INTO new_xp
    FROM user_xp_counts(target) c JOIN xp_rules r ON r.key = c.key;
    new_level := level_for_xp(new_xp);

    UPDATE users
    SET xp = new_xp,
        level = new_level,
        rank_name = (SELECT name FROM ranks WHERE min_level <= new_level ORDER BY min_level DESC LIMIT 1)
    WHERE id = target;
END;
$$;

CREATE FUNCTION content_progress_trigger() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    PERFORM refresh_user_progress(CASE WHEN TG_OP = 'DELETE' THEN OLD.user_id ELSE NEW.user_id END);
    RETURN NULL;
END;
$$;

CREATE FUNCTION like_progress_trigger() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    source record;
    author uuid;
BEGIN
    source := CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    IF TG_TABLE_NAME = 'review_likes' THEN
        SELECT user_id INTO author FROM reviews WHERE id = source.review_id;
    ELSIF TG_TABLE_NAME = 'post_likes' THEN
        SELECT user_id INTO author FROM posts WHERE id = source.post_id;
    ELSE
        SELECT user_id INTO author FROM comments WHERE id = source.comment_id;
    END IF;
    IF author IS NOT NULL THEN
        PERFORM refresh_user_progress(author);
    END IF;
    RETURN NULL;
END;
$$;

CREATE FUNCTION follow_progress_trigger() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    PERFORM refresh_user_progress(CASE WHEN TG_OP = 'DELETE' THEN OLD.followed_id ELSE NEW.followed_id END);
    RETURN NULL;
END;
$$;

CREATE TRIGGER reviews_progress AFTER INSERT OR DELETE OR UPDATE OF deleted_at ON reviews
    FOR EACH ROW EXECUTE FUNCTION content_progress_trigger();
CREATE TRIGGER posts_progress AFTER INSERT OR DELETE OR UPDATE OF deleted_at ON posts
    FOR EACH ROW EXECUTE FUNCTION content_progress_trigger();
CREATE TRIGGER comments_progress AFTER INSERT OR DELETE OR UPDATE OF deleted_at ON comments
    FOR EACH ROW EXECUTE FUNCTION content_progress_trigger();

CREATE TRIGGER review_likes_progress AFTER INSERT OR DELETE ON review_likes
    FOR EACH ROW EXECUTE FUNCTION like_progress_trigger();
CREATE TRIGGER post_likes_progress AFTER INSERT OR DELETE ON post_likes
    FOR EACH ROW EXECUTE FUNCTION like_progress_trigger();
CREATE TRIGGER comment_likes_progress AFTER INSERT OR DELETE ON comment_likes
    FOR EACH ROW EXECUTE FUNCTION like_progress_trigger();

CREATE TRIGGER follows_progress AFTER INSERT OR DELETE ON follows
    FOR EACH ROW EXECUTE FUNCTION follow_progress_trigger();

-- Contadores voltam a refletir só o que existe de verdade
UPDATE reviews r SET
    like_count = (SELECT count(*) FROM review_likes l WHERE l.review_id = r.id),
    comment_count = (SELECT count(*) FROM comments c WHERE c.review_id = r.id AND c.deleted_at IS NULL);

UPDATE posts p SET
    like_count = (SELECT count(*) FROM post_likes l WHERE l.post_id = p.id),
    comment_count = (SELECT count(*) FROM comments c WHERE c.post_id = p.id AND c.deleted_at IS NULL);

SELECT refresh_user_progress(id) FROM users;
