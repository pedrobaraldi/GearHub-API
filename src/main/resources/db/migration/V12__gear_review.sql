-- Equipamento pode apontar para uma review específica do produto
ALTER TABLE user_game_gear ADD COLUMN review_id uuid REFERENCES reviews (id) ON DELETE SET NULL;
