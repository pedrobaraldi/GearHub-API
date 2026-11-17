-- Publicidade (MVP): espaços configuráveis, anunciantes, campanhas, criativos e eventos (impressões e cliques).
-- O conteúdo do anúncio é só por campos (sem HTML nem script). Nada é apagado de verdade (exclusão lógica).

-- Espaço de anúncio: o site acha pelo slot_key, nunca pelo nome que o admin vê
CREATE TABLE ad_slots (
    id               serial PRIMARY KEY,
    slot_key         text NOT NULL UNIQUE CHECK (slot_key ~ '^[a-z0-9][a-z0-9_]{1,59}$'),
    name             text NOT NULL CHECK (length(name) BETWEEN 2 AND 80),
    description      text CHECK (length(description) <= 500),
    page             text NOT NULL CHECK (length(page) <= 60),
    position         text CHECK (length(position) <= 60),
    format           text NOT NULL CHECK (format IN ('banner', 'panel', 'promo_card', 'brand', 'institutional')),
    width            integer CHECK (width BETWEEN 1 AND 4000),
    height           integer CHECK (height BETWEEN 1 AND 4000),
    aspect_ratio     text CHECK (aspect_ratio ~ '^[0-9]{1,2}:[0-9]{1,2}$'),
    text_limit       integer NOT NULL DEFAULT 140 CHECK (text_limit BETWEEN 10 AND 1000),
    max_items        integer NOT NULL DEFAULT 1 CHECK (max_items BETWEEN 1 AND 10),
    priority         integer NOT NULL DEFAULT 0 CHECK (priority BETWEEN 0 AND 100),
    rotation_enabled boolean NOT NULL DEFAULT true,
    status           text NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'inactive')),
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    deleted_at       timestamptz
);

-- Os três espaços que o site já sabe exibir
INSERT INTO ad_slots (slot_key, name, description, page, position, format, width, height, aspect_ratio, text_limit, max_items) VALUES
    ('feed_inline', 'Card no feed', 'Card patrocinado entre as publicações do feed', 'feed', 'entre publicações', 'promo_card', 1200, 628, '16:9', 140, 1),
    ('sidebar_card', 'Card na lateral do feed', 'Card na barra lateral do feed, abaixo dos destaques', 'feed', 'barra lateral', 'promo_card', 600, 600, '1:1', 120, 1),
    ('explore_card', 'Card no Explorar', 'Card patrocinado no topo da tela Explorar', 'explore', 'topo', 'promo_card', 1200, 628, '16:9', 140, 1);

-- Anunciante: independente das campanhas. CPF/CNPJ criptografado
CREATE TABLE advertisers (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    display_name       text NOT NULL CHECK (length(display_name) BETWEEN 2 AND 80),
    legal_name         text CHECK (length(legal_name) <= 160),
    document_encrypted text,
    contact_name       text CHECK (length(contact_name) <= 120),
    email              text CHECK (length(email) <= 254),
    phone              text CHECK (phone ~ '^[0-9]{10,11}$'),
    logo_url           text,
    website_url        text CHECK (length(website_url) <= 300),
    social_links       jsonb NOT NULL DEFAULT '[]'::jsonb,
    notes              text CHECK (length(notes) <= 2000),
    status             text NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'inactive')),
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    deleted_at         timestamptz
);

-- Campanha. Agendada, ativa e encerrada são calculadas pelas datas de uma campanha aprovada
CREATE TABLE ad_campaigns (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    advertiser_id    uuid NOT NULL REFERENCES advertisers (id),
    name             text NOT NULL CHECK (length(name) BETWEEN 2 AND 120),
    description      text CHECK (length(description) <= 2000),
    start_at         timestamptz NOT NULL,
    end_at           timestamptz NOT NULL,
    priority         integer NOT NULL DEFAULT 0 CHECK (priority BETWEEN 0 AND 100),
    commercial_model text NOT NULL DEFAULT 'fixed' CHECK (commercial_model IN ('fixed', 'cpm', 'cpc', 'package', 'exclusive')),
    commercial_value numeric(12, 2) CHECK (commercial_value >= 0),
    impression_limit integer CHECK (impression_limit > 0),
    status           text NOT NULL DEFAULT 'draft'
        CHECK (status IN ('draft', 'pending_approval', 'approved', 'paused', 'rejected')),
    review_note      text CHECK (length(review_note) <= 1000),
    reviewed_by      uuid REFERENCES users (id),
    reviewed_at      timestamptz,
    created_by       uuid REFERENCES users (id),
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    deleted_at       timestamptz,
    CHECK (end_at > start_at)
);
CREATE INDEX idx_ad_campaigns_live ON ad_campaigns (start_at, end_at) WHERE status = 'approved' AND deleted_at IS NULL;

-- Criativo: o que aparece para a pessoa. Só campos, nunca HTML
CREATE TABLE ad_creatives (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    campaign_id      uuid NOT NULL REFERENCES ad_campaigns (id),
    title            text CHECK (length(title) <= 120),
    description      text CHECK (length(description) <= 1000),
    image_url        text,
    mobile_image_url text,
    logo_url         text,
    destination_url  text NOT NULL CHECK (destination_url ~ '^https://' AND length(destination_url) <= 500),
    cta_text         text CHECK (length(cta_text) <= 30),
    alt_text         text CHECK (length(alt_text) <= 200),
    status           text NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'inactive')),
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    deleted_at       timestamptz
);
CREATE INDEX idx_ad_creatives_campaign ON ad_creatives (campaign_id) WHERE deleted_at IS NULL;

-- Em quais espaços a campanha aparece
CREATE TABLE ad_campaign_slots (
    campaign_id uuid NOT NULL REFERENCES ad_campaigns (id),
    slot_id     integer NOT NULL REFERENCES ad_slots (id),
    priority    integer NOT NULL DEFAULT 0 CHECK (priority BETWEEN 0 AND 100),
    created_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (campaign_id, slot_id)
);

-- Impressões e cliques. Sessão só como hash de um identificador aleatório: sem IP nem conta
CREATE TABLE ad_events (
    id           bigserial PRIMARY KEY,
    campaign_id  uuid NOT NULL REFERENCES ad_campaigns (id),
    slot_id      integer NOT NULL REFERENCES ad_slots (id),
    creative_id  uuid NOT NULL REFERENCES ad_creatives (id),
    event_type   text NOT NULL CHECK (event_type IN ('impression', 'click')),
    session_hash text NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_ad_events_campaign ON ad_events (campaign_id, event_type, created_at);
CREATE INDEX idx_ad_events_dedupe ON ad_events (creative_id, slot_id, session_hash, event_type, created_at DESC);
