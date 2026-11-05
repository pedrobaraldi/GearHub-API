-- Por enquanto só o produto é exigido na review.
-- Para voltar a exigir a ficha técnica, basta marcar spec_fields.required de novo.
UPDATE spec_fields SET required = false;

ALTER TABLE reviews
    ALTER COLUMN title DROP NOT NULL,
    ALTER COLUMN body DROP NOT NULL,
    ALTER COLUMN score DROP NOT NULL,
    DROP CONSTRAINT reviews_title_check,
    DROP CONSTRAINT reviews_body_check,
    ADD CONSTRAINT reviews_title_check CHECK (char_length(title) BETWEEN 1 AND 120),
    ADD CONSTRAINT reviews_body_check CHECK (char_length(body) BETWEEN 1 AND 5000);
