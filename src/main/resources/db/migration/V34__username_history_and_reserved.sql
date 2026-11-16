-- Histórico de @: quem troca de @ segura o antigo por 15 dias (só ela pode voltar a ele nesse prazo).
-- Depois o @ fica livre. As linhas nunca são apagadas: servem também de histórico do perfil
CREATE TABLE username_history (
    id         bigserial PRIMARY KEY,
    user_id    uuid NOT NULL REFERENCES users (id),
    username   text NOT NULL,
    changed_at timestamptz NOT NULL DEFAULT now(),
    held_until timestamptz NOT NULL
);
CREATE INDEX idx_username_history_name ON username_history (username, held_until);
CREATE INDEX idx_username_history_user ON username_history (user_id, changed_at DESC);

-- Nomes reservados: ninguém pega no cadastro, na troca de @ ou no @ gerado pelo login social.
-- Gerenciados pelo painel de admin, que também entrega um nome reservado a uma conta (contas oficiais).
-- match_type: exact (o @ inteiro), prefix (começa com) ou contains (contém)
CREATE TABLE reserved_usernames (
    pattern     text PRIMARY KEY CHECK (pattern ~ '^[a-z0-9]{2,30}$'),
    match_type  text NOT NULL DEFAULT 'exact' CHECK (match_type IN ('exact', 'prefix', 'contains')),
    reason      text CHECK (length(reason) <= 200),
    created_by  uuid REFERENCES users (id),
    created_at  timestamptz NOT NULL DEFAULT now(),
    -- Conta oficial que recebeu o nome (só para match_type exact)
    assigned_to uuid REFERENCES users (id),
    assigned_at timestamptz,
    -- Exclusão lógica: a reserva deixa de valer, mas o registro fica
    removed_at  timestamptz,
    removed_by  uuid REFERENCES users (id),
    CHECK (assigned_to IS NULL OR match_type = 'exact')
);

INSERT INTO reserved_usernames (pattern, match_type, reason) VALUES
    ('gearhub', 'contains', 'Marca do site'),
    ('admin', 'prefix', 'Equipe'),
    ('administrador', 'exact', 'Equipe'),
    ('administracao', 'exact', 'Equipe'),
    ('moderador', 'prefix', 'Equipe'),
    ('moderadora', 'exact', 'Equipe'),
    ('moderacao', 'exact', 'Equipe'),
    ('mod', 'exact', 'Equipe'),
    ('staff', 'exact', 'Equipe'),
    ('equipe', 'exact', 'Equipe'),
    ('team', 'exact', 'Equipe'),
    ('suporte', 'prefix', 'Atendimento'),
    ('support', 'exact', 'Atendimento'),
    ('ajuda', 'exact', 'Atendimento'),
    ('help', 'exact', 'Atendimento'),
    ('contato', 'exact', 'Atendimento'),
    ('atendimento', 'exact', 'Atendimento'),
    ('oficial', 'exact', 'Contas oficiais'),
    ('official', 'exact', 'Contas oficiais'),
    ('seguranca', 'exact', 'Segurança'),
    ('security', 'exact', 'Segurança'),
    ('sistema', 'exact', 'Sistema'),
    ('system', 'exact', 'Sistema'),
    ('root', 'exact', 'Sistema'),
    ('api', 'exact', 'Sistema'),
    ('www', 'exact', 'Sistema'),
    ('null', 'exact', 'Sistema'),
    ('undefined', 'exact', 'Sistema'),
    ('anonimo', 'exact', 'Sistema'),
    ('usuario', 'exact', 'Sistema');
