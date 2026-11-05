-- Schema inicial do GearHub (v1: núcleo da comunidade)

-- Contas -------------------------------------------------------------------

CREATE TABLE users (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    username          text NOT NULL UNIQUE CHECK (username ~ '^[a-z0-9]{3,30}$'),
    display_name      text NOT NULL CHECK (char_length(display_name) BETWEEN 1 AND 60),
    bio               text CHECK (char_length(bio) <= 300),
    avatar_url        text,
    -- E-mail: AES-256-GCM. O HMAC (índice cego) permite buscar e garantir unicidade.
    email_encrypted   text,
    email_hash        text UNIQUE,
    email_verified_at timestamptz,
    -- Argon2id. Nulo em contas que só entram por Google ou Discord.
    password_hash     text,
    role              text NOT NULL DEFAULT 'user' CHECK (role IN ('user', 'admin')),
    rank_name         text NOT NULL DEFAULT 'Explorador',
    level             integer NOT NULL DEFAULT 1 CHECK (level >= 1),
    is_pro            boolean NOT NULL DEFAULT false,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    deleted_at        timestamptz
);

CREATE TABLE oauth_accounts (
    id                         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                    uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    provider                   text NOT NULL CHECK (provider IN ('google', 'discord')),
    provider_user_id_encrypted text NOT NULL,
    provider_user_id_hash      text NOT NULL,
    created_at                 timestamptz NOT NULL DEFAULT now(),
    UNIQUE (provider, provider_user_id_hash)
);

CREATE INDEX idx_oauth_accounts_user ON oauth_accounts (user_id);

CREATE TABLE sessions (
    id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id              uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- SHA-256 do token do cookie: o token em si nunca é guardado.
    token_hash           text NOT NULL UNIQUE,
    ip_encrypted         text,
    user_agent_encrypted text,
    expires_at           timestamptz NOT NULL,
    last_seen_at         timestamptz NOT NULL DEFAULT now(),
    created_at           timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_sessions_user ON sessions (user_id);
CREATE INDEX idx_sessions_expires ON sessions (expires_at);

CREATE TABLE email_tokens (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash text NOT NULL UNIQUE,
    purpose    text NOT NULL CHECK (purpose IN ('verify_email', 'reset_password')),
    expires_at timestamptz NOT NULL,
    used_at    timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_email_tokens_user ON email_tokens (user_id);

-- Catálogo -----------------------------------------------------------------

CREATE TABLE categories (
    id       integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    slug     text NOT NULL UNIQUE,
    name     text NOT NULL UNIQUE,
    position integer NOT NULL DEFAULT 0
);

CREATE TABLE criteria (
    id          integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    category_id integer NOT NULL REFERENCES categories (id) ON DELETE CASCADE,
    label       text NOT NULL,
    position    integer NOT NULL DEFAULT 0,
    UNIQUE (category_id, label)
);

CREATE TABLE products (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    slug         text NOT NULL UNIQUE,
    name         text NOT NULL,
    brand        text NOT NULL,
    category_id  integer NOT NULL REFERENCES categories (id),
    image_url    text,
    about        text,
    specs        jsonb NOT NULL DEFAULT '[]',
    avg_score    numeric(3, 1) CHECK (avg_score BETWEEN 0 AND 10),
    review_count integer NOT NULL DEFAULT 0 CHECK (review_count >= 0),
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_products_category ON products (category_id);
CREATE INDEX idx_products_ranking ON products (avg_score DESC NULLS LAST, review_count DESC);

-- Conteúdo -----------------------------------------------------------------

CREATE TABLE reviews (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       uuid NOT NULL REFERENCES users (id),
    product_id    uuid NOT NULL REFERENCES products (id),
    title         text NOT NULL CHECK (char_length(title) BETWEEN 3 AND 120),
    body          text NOT NULL CHECK (char_length(body) BETWEEN 10 AND 5000),
    -- Média dos critérios, calculada pelo backend.
    score         numeric(3, 1) NOT NULL CHECK (score BETWEEN 0 AND 10),
    cover_url     text,
    like_count    integer NOT NULL DEFAULT 0 CHECK (like_count >= 0),
    comment_count integer NOT NULL DEFAULT 0 CHECK (comment_count >= 0),
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    deleted_at    timestamptz
);

-- Uma review por pessoa e produto, ignorando as apagadas.
CREATE UNIQUE INDEX uq_reviews_user_product ON reviews (user_id, product_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_reviews_feed ON reviews (created_at DESC, id DESC) WHERE deleted_at IS NULL;
CREATE INDEX idx_reviews_product ON reviews (product_id) WHERE deleted_at IS NULL;

CREATE TABLE review_scores (
    review_id    uuid NOT NULL REFERENCES reviews (id) ON DELETE CASCADE,
    criterion_id integer NOT NULL REFERENCES criteria (id),
    value        numeric(3, 1) NOT NULL CHECK (value BETWEEN 0 AND 10),
    PRIMARY KEY (review_id, criterion_id)
);

CREATE TABLE review_pros_cons (
    id        uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    review_id uuid NOT NULL REFERENCES reviews (id) ON DELETE CASCADE,
    kind      text NOT NULL CHECK (kind IN ('pro', 'con')),
    text      text NOT NULL CHECK (char_length(text) BETWEEN 1 AND 120),
    position  integer NOT NULL DEFAULT 0,
    UNIQUE (review_id, kind, position)
);

CREATE TABLE comments (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    review_id  uuid NOT NULL REFERENCES reviews (id) ON DELETE CASCADE,
    user_id    uuid NOT NULL REFERENCES users (id),
    body       text NOT NULL CHECK (char_length(body) BETWEEN 1 AND 2000),
    like_count integer NOT NULL DEFAULT 0 CHECK (like_count >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz
);

CREATE INDEX idx_comments_review ON comments (review_id, created_at) WHERE deleted_at IS NULL;

-- Social -------------------------------------------------------------------

CREATE TABLE review_likes (
    review_id  uuid NOT NULL REFERENCES reviews (id) ON DELETE CASCADE,
    user_id    uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (review_id, user_id)
);

CREATE TABLE comment_likes (
    comment_id uuid NOT NULL REFERENCES comments (id) ON DELETE CASCADE,
    user_id    uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (comment_id, user_id)
);

CREATE TABLE saved_reviews (
    review_id  uuid NOT NULL REFERENCES reviews (id) ON DELETE CASCADE,
    user_id    uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, review_id)
);

CREATE TABLE follows (
    follower_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    followed_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (follower_id, followed_id),
    CHECK (follower_id <> followed_id)
);

CREATE INDEX idx_follows_followed ON follows (followed_id);
