package com.gearhub.account;

import com.gearhub.users.User;
import com.gearhub.users.UserRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Cópia dos dados da pessoa (LGPD, art. 18): perfil, setup, jogos, publicações, comentários, quem segue,
 * insígnias e sessões. Segredos ficam de fora: senha, segredo do 2FA e tokens nunca saem.
 */
@Service
public class AccountExport {

    private final UserRepository users;
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public AccountExport(UserRepository users, JdbcClient jdbc, ObjectMapper json) {
        this.users = users;
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> of(UUID userId) {
        User user = users.findById(userId).orElseThrow();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("geradoEm", Instant.now().toString());
        data.put("conta", section("""
                SELECT jsonb_build_object(
                    'usuario', username, 'nome', display_name, 'bio', bio, 'site', website, 'twitter', twitter,
                    'instagram', instagram, 'youtube', youtube, 'foto', avatar_url, 'banner', banner_url,
                    'nivel', level, 'xp', xp, 'patente', rank_name, 'criadaEm', created_at,
                    'verificacaoEmDuasEtapas', two_factor_enabled_at IS NOT NULL)
                FROM users WHERE id = :user
                """, userId));
        data.put("email", user.getEmail());
        data.put("setup", section("SELECT jsonb_build_object('pecas', setup, 'fotos', setup_photos) FROM users WHERE id = :user", userId));
        data.put("jogos", section("""
                SELECT coalesce(jsonb_agg(jsonb_build_object('jogo', g.name) || (to_jsonb(ug) - 'user_id' - 'game_id')), '[]')
                FROM user_games ug JOIN games g ON g.id = ug.game_id WHERE ug.user_id = :user
                """, userId));
        data.put("posts", section("""
                SELECT coalesce(jsonb_agg(jsonb_build_object('texto', body, 'tags', tags, 'imagens', images,
                       'curtidas', like_count, 'criadoEm', created_at) ORDER BY created_at), '[]')
                FROM posts WHERE user_id = :user AND deleted_at IS NULL
                """, userId));
        data.put("reviews", section("""
                SELECT coalesce(jsonb_agg(jsonb_build_object('produto', p.brand || ' ' || p.name, 'titulo', r.title,
                       'texto', r.body, 'nota', r.score, 'fichaTecnica', r.specs, 'imagens', r.images,
                       'curtidas', r.like_count, 'criadaEm', r.created_at) ORDER BY r.created_at), '[]')
                FROM reviews r JOIN products p ON p.id = r.product_id WHERE r.user_id = :user AND r.deleted_at IS NULL
                """, userId));
        data.put("comentarios", section("""
                SELECT coalesce(jsonb_agg(jsonb_build_object('texto', body, 'criadoEm', created_at) ORDER BY created_at), '[]')
                FROM comments WHERE user_id = :user AND deleted_at IS NULL
                """, userId));
        data.put("seguindo", section("""
                SELECT coalesce(jsonb_agg(u.username ORDER BY u.username), '[]')
                FROM follows f JOIN users u ON u.id = f.followed_id WHERE f.follower_id = :user
                """, userId));
        data.put("seguidores", section("""
                SELECT coalesce(jsonb_agg(u.username ORDER BY u.username), '[]')
                FROM follows f JOIN users u ON u.id = f.follower_id WHERE f.followed_id = :user
                """, userId));
        data.put("insignias", section("""
                SELECT coalesce(jsonb_agg(jsonb_build_object('insignia', b.name, 'recebidaEm', ub.granted_at)), '[]')
                FROM user_badges ub JOIN badges b ON b.key = ub.badge_key WHERE ub.user_id = :user
                """, userId));
        data.put("contasLigadas", section("""
                SELECT coalesce(jsonb_agg(jsonb_build_object('provedor', provider, 'ligadaEm', created_at)), '[]')
                FROM oauth_accounts WHERE user_id = :user
                """, userId));
        data.put("sessoes", section("""
                SELECT coalesce(jsonb_agg(jsonb_build_object('abertaEm', created_at, 'expiraEm', expires_at)
                       ORDER BY created_at DESC), '[]')
                FROM sessions WHERE user_id = :user
                """, userId));
        return data;
    }

    private Object section(String sql, UUID userId) {
        String value = jdbc.sql("SELECT (" + sql + ")::text").param("user", userId).query(String.class).single();
        return value == null ? null : json.readValue(value, Object.class);
    }
}
