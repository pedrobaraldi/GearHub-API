-- Curtidas, salvos e comentários passam a valer também para posts

CREATE TABLE post_likes (
    post_id    uuid NOT NULL REFERENCES posts (id) ON DELETE CASCADE,
    user_id    uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (post_id, user_id)
);

CREATE TABLE saved_posts (
    post_id    uuid NOT NULL REFERENCES posts (id) ON DELETE CASCADE,
    user_id    uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, post_id)
);

-- Um comentário pertence a uma review ou a um post, nunca aos dois
ALTER TABLE comments ALTER COLUMN review_id DROP NOT NULL;
ALTER TABLE comments ADD COLUMN post_id uuid REFERENCES posts (id) ON DELETE CASCADE;
ALTER TABLE comments ADD CONSTRAINT comments_single_parent CHECK (num_nonnulls(review_id, post_id) = 1);

CREATE INDEX idx_comments_post ON comments (post_id, created_at) WHERE deleted_at IS NULL;
