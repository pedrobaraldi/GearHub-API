-- Posts livres: diferente da review, não exigem produto nem notas

CREATE TABLE posts (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       uuid NOT NULL REFERENCES users (id),
    category_id   integer REFERENCES categories (id),
    title         text NOT NULL CHECK (char_length(title) BETWEEN 3 AND 120),
    body          text NOT NULL CHECK (char_length(body) BETWEEN 1 AND 5000),
    like_count    integer NOT NULL DEFAULT 0 CHECK (like_count >= 0),
    comment_count integer NOT NULL DEFAULT 0 CHECK (comment_count >= 0),
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    deleted_at    timestamptz
);

CREATE INDEX idx_posts_feed ON posts (created_at DESC) WHERE deleted_at IS NULL;
CREATE INDEX idx_posts_user ON posts (user_id) WHERE deleted_at IS NULL;
