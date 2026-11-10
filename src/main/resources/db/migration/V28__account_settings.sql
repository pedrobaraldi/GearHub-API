-- Configurações da conta: insígnia de quem protege a conta e exclusão definitiva depois do prazo

-- Insígnia de quem ativa a verificação em duas etapas, com XP próprio
INSERT INTO badges (key, name, description, position)
SELECT 'protected', 'Conta protegida', 'Ativou a verificação em duas etapas', coalesce(max(position), 0) + 1 FROM badges;

INSERT INTO xp_rules (key, label, points, position)
SELECT 'protected', 'Conta protegida (2FA)', 25, coalesce(max(position), 0) + 1 FROM xp_rules;

CREATE OR REPLACE FUNCTION user_xp_counts(target uuid) RETURNS TABLE (key text, total bigint)
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
    UNION ALL
    SELECT 'protected', count(*) FROM user_badges WHERE user_id = target AND badge_key = 'protected'
$$;

-- Passados os 30 dias, apagar o usuário leva junto o que ele publicou
ALTER TABLE reviews DROP CONSTRAINT reviews_user_id_fkey,
    ADD CONSTRAINT reviews_user_id_fkey FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE;
ALTER TABLE comments DROP CONSTRAINT comments_user_id_fkey,
    ADD CONSTRAINT comments_user_id_fkey FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE;
ALTER TABLE posts DROP CONSTRAINT posts_user_id_fkey,
    ADD CONSTRAINT posts_user_id_fkey FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE;

CREATE INDEX idx_users_deleted ON users (deleted_at) WHERE deleted_at IS NOT NULL;
