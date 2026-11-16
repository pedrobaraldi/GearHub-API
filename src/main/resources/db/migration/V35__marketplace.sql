-- Marketplace e lojas parceiras (Fase 1 + link rastreável). A compra acontece no site da loja: aqui só a
-- vitrine, a aprovação das lojas e o registro dos cliques. Nada é apagado de verdade (exclusão lógica).

-- Solicitação de parceria. CPF criptografado (com HMAC para achar duplicadas sem descriptografar)
CREATE TABLE partner_applications (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id            uuid NOT NULL REFERENCES users (id),
    status             text NOT NULL DEFAULT 'draft'
        CHECK (status IN ('draft', 'pending', 'in_review', 'awaiting_info', 'approved', 'rejected')),
    -- Responsável
    responsible_name   text CHECK (length(responsible_name) <= 120),
    cpf_encrypted      text,
    cpf_hash           text,
    contact_email      text CHECK (length(contact_email) <= 254),
    phone              text CHECK (phone ~ '^[0-9]{10,11}$'),
    role_in_company    text CHECK (length(role_in_company) <= 80),
    -- Empresa (dados públicos do CNPJ)
    cnpj               text CHECK (cnpj ~ '^[0-9]{14}$'),
    legal_name         text CHECK (length(legal_name) <= 160),
    trade_name         text CHECK (length(trade_name) <= 80),
    segment            text CHECK (length(segment) <= 80),
    address            text CHECK (length(address) <= 300),
    website            text CHECK (length(website) <= 200),
    -- Complementares
    description        text CHECK (length(description) <= 2000),
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    submitted_at       timestamptz,
    -- Última decisão do admin
    decided_by         uuid REFERENCES users (id),
    decided_at         timestamptz,
    decision_note      text CHECK (length(decision_note) <= 1000)
);
-- Uma solicitação em andamento por pessoa
CREATE UNIQUE INDEX uq_partner_applications_open ON partner_applications (user_id)
    WHERE status IN ('draft', 'pending', 'in_review', 'awaiting_info');
CREATE INDEX idx_partner_applications_status ON partner_applications (status, submitted_at);
CREATE INDEX idx_partner_applications_cnpj ON partner_applications (cnpj);

-- Histórico de cada mudança de status: quem, quando e por quê
CREATE TABLE partner_application_events (
    id             bigserial PRIMARY KEY,
    application_id uuid NOT NULL REFERENCES partner_applications (id),
    from_status    text,
    to_status      text NOT NULL,
    actor_id       uuid REFERENCES users (id),
    note           text CHECK (length(note) <= 1000),
    created_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_partner_application_events ON partner_application_events (application_id, created_at);

-- Documentos da validação. O arquivo fica criptografado numa pasta que o site não publica
CREATE TABLE partner_documents (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    application_id uuid NOT NULL REFERENCES partner_applications (id),
    kind           text NOT NULL CHECK (kind IN ('cnpj_card', 'social_contract', 'responsible_id', 'other')),
    stored_name    text NOT NULL UNIQUE,
    content_type   text NOT NULL CHECK (content_type IN ('application/pdf', 'image/jpeg', 'image/png')),
    size_bytes     integer NOT NULL CHECK (size_bytes > 0),
    created_at     timestamptz NOT NULL DEFAULT now(),
    deleted_at     timestamptz
);
CREATE INDEX idx_partner_documents_application ON partner_documents (application_id) WHERE deleted_at IS NULL;

-- Loja aprovada. Nasce na aprovação: só então a pessoa vira lojista
CREATE TABLE partner_stores (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    application_id uuid NOT NULL UNIQUE REFERENCES partner_applications (id),
    owner_id       uuid NOT NULL REFERENCES users (id),
    slug           text NOT NULL UNIQUE CHECK (slug ~ '^[a-z0-9][a-z0-9-]{1,59}$'),
    name           text NOT NULL CHECK (length(name) BETWEEN 2 AND 80),
    description    text CHECK (length(description) <= 2000),
    logo_url       text,
    banner_url     text,
    segment        text CHECK (length(segment) <= 80),
    website        text CHECK (length(website) <= 200),
    instagram      text CHECK (length(instagram) <= 60),
    status         text NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'suspended')),
    suspended_note text CHECK (length(suspended_note) <= 1000),
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    deleted_at     timestamptz
);
-- Uma loja por conta
CREATE UNIQUE INDEX uq_partner_stores_owner ON partner_stores (owner_id) WHERE deleted_at IS NULL;

-- Produto da loja (separado do catálogo de reviews). Preço é só informativo: quem vende é a loja
CREATE TABLE store_products (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id       uuid NOT NULL REFERENCES partner_stores (id),
    name           text NOT NULL CHECK (length(name) BETWEEN 2 AND 120),
    description    text CHECK (length(description) <= 3000),
    images         jsonb NOT NULL DEFAULT '[]'::jsonb,
    category_id    integer REFERENCES categories (id),
    price          numeric(12, 2) CHECK (price >= 0),
    availability   text NOT NULL DEFAULT 'in_stock' CHECK (availability IN ('in_stock', 'out_of_stock', 'preorder')),
    purchase_url   text NOT NULL CHECK (purchase_url ~ '^https://' AND length(purchase_url) <= 500),
    status         text NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'paused', 'archived')),
    -- Moderação: o admin tira do ar sem apagar
    removed_at     timestamptz,
    removed_by     uuid REFERENCES users (id),
    removed_note   text CHECK (length(removed_note) <= 1000),
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    deleted_at     timestamptz
);
CREATE INDEX idx_store_products_store ON store_products (store_id, created_at DESC) WHERE deleted_at IS NULL;
CREATE INDEX idx_store_products_showcase ON store_products (created_at DESC)
    WHERE deleted_at IS NULL AND removed_at IS NULL AND status = 'active';

-- Clique no "Comprar na loja". O id é o click_id que vai junto para a loja, para atribuir a venda depois.
-- Sem IP: o mínimo de dado pessoal (a conta só quando a pessoa está logada)
CREATE TABLE click_events (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id uuid NOT NULL REFERENCES store_products (id),
    store_id   uuid NOT NULL REFERENCES partner_stores (id),
    user_id    uuid REFERENCES users (id),
    source     text CHECK (length(source) <= 40),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_click_events_store ON click_events (store_id, created_at DESC);
CREATE INDEX idx_click_events_product ON click_events (product_id, created_at DESC);

-- Notificação de mudança na solicitação de loja
ALTER TABLE notifications DROP CONSTRAINT notifications_type_check;
ALTER TABLE notifications ADD CONSTRAINT notifications_type_check
    CHECK (type IN ('like', 'comment', 'reply', 'follow', 'rank_up', 'partner'));
