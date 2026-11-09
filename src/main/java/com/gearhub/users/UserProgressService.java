package com.gearhub.users;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Lê o progresso que o banco mantém atualizado a cada ação: ver a migração V14. */
@Service
public class UserProgressService {

    public record Activity(String key, String label, int points, long count, long xp) {
    }

    public record Rank(String name, int minLevel, boolean reached, boolean current) {
    }

    @Schema(description = "`levelStartXp` e `nextLevelXp` delimitam a barra do nível atual")
    public record Progress(String username, String displayName, String avatarUrl, boolean pro, String rankName,
            int level, int xp, int levelStartXp, int nextLevelXp, List<Activity> activities, List<Rank> ranks,
            List<BadgeService.Badge> badges) {
    }

    private record Person(UUID id, String username, String displayName, String avatarUrl, boolean pro, String rankName,
            int level, int xp, int levelStartXp, int nextLevelXp) {
    }

    private final JdbcClient jdbc;
    private final BadgeService badges;

    public UserProgressService(JdbcClient jdbc, BadgeService badges) {
        this.jdbc = jdbc;
        this.badges = badges;
    }

    @Transactional(readOnly = true)
    public Progress progress(String username) {
        Person person = jdbc.sql("""
                SELECT id, username, display_name, avatar_url, is_pro, rank_name, level, xp,
                       xp_for_level(level) AS start_xp, xp_for_level(level + 1) AS next_xp
                FROM users WHERE username = :username AND deleted_at IS NULL
                """)
                .param("username", username)
                .query((rs, row) -> new Person(rs.getObject("id", UUID.class), rs.getString("username"),
                        rs.getString("display_name"), rs.getString("avatar_url"), rs.getBoolean("is_pro"),
                        rs.getString("rank_name"), rs.getInt("level"), rs.getInt("xp"), rs.getInt("start_xp"),
                        rs.getInt("next_xp")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuário não encontrado"));

        List<Activity> activities = jdbc.sql("""
                SELECT r.key, r.label, r.points, c.total FROM xp_rules r
                JOIN user_xp_counts(:user) c ON c.key = r.key ORDER BY r.position
                """)
                .param("user", person.id())
                .query((rs, row) -> new Activity(rs.getString("key"), rs.getString("label"), rs.getInt("points"),
                        rs.getLong("total"), rs.getLong("total") * rs.getInt("points")))
                .list();

        List<Rank> ranks = jdbc.sql("SELECT name, min_level FROM ranks ORDER BY min_level")
                .query((rs, row) -> new Rank(rs.getString("name"), rs.getInt("min_level"),
                        person.level() >= rs.getInt("min_level"), rs.getString("name").equals(person.rankName())))
                .list();

        return new Progress(person.username(), person.displayName(), person.avatarUrl(), person.pro(), person.rankName(),
                person.level(), person.xp(), person.levelStartXp(), person.nextLevelXp(), activities, ranks,
                badges.of(person.id()));
    }
}
