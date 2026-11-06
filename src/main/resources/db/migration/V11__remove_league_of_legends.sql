-- League of Legends saiu do GearHub. Configurações e equipamentos do jogo vão junto
DELETE FROM user_games WHERE game_id = (SELECT id FROM games WHERE slug = 'league-of-legends');
DELETE FROM games WHERE slug = 'league-of-legends';
