package com.gearhub.notifications;

import io.swagger.v3.oas.annotations.media.Schema;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Notificações no site. Nascem nas ações de outras pessoas (curtir, comentar, responder, seguir) e na
 * subida de rank (pelo banco). Ninguém é notificado da própria ação, e cada um escolhe o que quer receber.
 */
@Service
public class NotificationService {

    private static final int SNIPPET = 120;
    private static final int MAX_PAGE = 50;

    @Schema(description = "Quem fez a ação")
    public record Actor(String username, String displayName, String avatarUrl) {
    }

    @Schema(description = "Uma notificação, já com o texto e o link prontos")
    public record Notification(long id,
            @Schema(allowableValues = {"like", "comment", "reply", "follow", "rank_up"}) String type,
            Actor actor,
            @Schema(example = "curtiu sua review") String message,
            @Schema(description = "Trecho do que recebeu a ação") String snippet,
            @Schema(example = "/product/razer-viper-v3-pro?review=…") String link,
            Instant createdAt, boolean read) {
    }

    public record Page(List<Notification> items, Instant nextCursor, int unread) {
    }

    @Schema(description = "O que a pessoa quer receber")
    public record Settings(boolean likes, boolean comments, boolean follows, boolean rankUps) {
    }

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public NotificationService(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // ---------- Quando alguém faz algo ----------

    /** Curtida em post ou review ({@code kind} = post/review) ou em comentário (comment). Descurtir apaga. */
    @Transactional
    public void liked(String kind, UUID targetId, UUID ownerId, UUID actorId, boolean liked) {
        if (!liked) {
            jdbc.sql("""
                    DELETE FROM notifications
                    WHERE user_id = :owner AND actor_id = :actor AND type = 'like' AND target_kind = :kind AND target_id = :target
                    """).param("owner", ownerId).param("actor", actorId).param("kind", kind).param("target", targetId).update();
            return;
        }
        create(ownerId, actorId, "like", kind, targetId, Map.of(), "likes");
    }

    /** Comentário em post ou review: avisa o dono; numa resposta, avisa também quem foi respondido. */
    @Transactional
    public void commented(String kind, UUID contentId, UUID ownerId, UUID actorId, String body, UUID repliedUserId) {
        Map<String, Object> data = Map.of("comment", snippet(body));
        if (repliedUserId != null) {
            create(repliedUserId, actorId, "reply", kind, contentId, data, "comments");
        }
        // Se o dono é quem foi respondido, uma notificação basta
        if (!ownerId.equals(repliedUserId)) {
            create(ownerId, actorId, "comment", kind, contentId, data, "comments");
        }
    }

    @Transactional
    public void followed(UUID followedId, UUID followerId, boolean following) {
        if (!following) {
            jdbc.sql("DELETE FROM notifications WHERE user_id = :user AND actor_id = :actor AND type = 'follow'")
                    .param("user", followedId).param("actor", followerId).update();
            return;
        }
        create(followedId, followerId, "follow", null, null, Map.of(), "follows");
    }

    private void create(UUID recipient, UUID actor, String type, String kind, UUID target, Map<String, Object> data,
            String setting) {
        if (recipient == null || recipient.equals(actor)) {
            return;
        }
        // O nome da coluna vem só dos valores fixos acima, nunca de quem chama a API
        jdbc.sql("""
                INSERT INTO notifications (user_id, actor_id, type, target_kind, target_id, data)
                SELECT :user, :actor, :type, :kind, :target, cast(:data AS jsonb)
                WHERE coalesce((SELECT %s FROM notification_settings WHERE user_id = :user), true)
                  AND EXISTS (SELECT 1 FROM users WHERE id = :user AND deleted_at IS NULL)
                ON CONFLICT DO NOTHING
                """.formatted(setting))
                .param("user", recipient).param("actor", actor).param("type", type)
                .param("kind", kind, java.sql.Types.VARCHAR).param("target", target, java.sql.Types.OTHER)
                .param("data", json.writeValueAsString(data))
                .update();
    }

    private static String snippet(String text) {
        if (text == null) {
            return "";
        }
        String clean = text.strip().replaceAll("\\s+", " ");
        return clean.length() <= SNIPPET ? clean : clean.substring(0, SNIPPET - 1) + "…";
    }

    // ---------- Leitura ----------

    @Transactional(readOnly = true)
    public Page page(UUID userId, Instant before, int size) {
        int limit = Math.max(1, Math.min(size, MAX_PAGE));
        List<Notification> items = jdbc.sql("""
                SELECT n.id, n.type, n.target_kind, n.data::text AS data, n.created_at, n.read_at,
                       a.username, a.display_name, a.avatar_url, me.username AS me,
                       CASE n.target_kind
                           WHEN 'post' THEN (SELECT body FROM posts WHERE id = n.target_id)
                           WHEN 'review' THEN (SELECT coalesce(title, body) FROM reviews WHERE id = n.target_id)
                           WHEN 'comment' THEN (SELECT body FROM comments WHERE id = n.target_id)
                       END AS target_text,
                       (SELECT coalesce('/games/' || g.slug || '#comunidade', '/product/' || p.slug || '?review=' || r.id)
                          FROM reviews r JOIN products p ON p.id = r.product_id
                          LEFT JOIN catalog_games g ON g.igdb_id = p.game_id
                         WHERE r.id = CASE WHEN n.target_kind = 'review' THEN n.target_id
                                           WHEN n.target_kind = 'comment' THEN (SELECT review_id FROM comments WHERE id = n.target_id)
                                      END) AS review_link
                FROM notifications n
                JOIN users me ON me.id = n.user_id
                LEFT JOIN users a ON a.id = n.actor_id
                WHERE n.user_id = :user AND (n.actor_id IS NULL OR a.deleted_at IS NULL)
                  AND (CAST(:before AS timestamptz) IS NULL OR n.created_at < :before)
                ORDER BY n.created_at DESC, n.id DESC LIMIT :limit
                """)
                .param("user", userId)
                .param("before", before == null ? null : Timestamp.from(before), java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                .param("limit", limit)
                .query((rs, row) -> {
                    String type = rs.getString("type");
                    String kind = rs.getString("target_kind");
                    Map<String, Object> data = json.readValue(rs.getString("data"), new TypeReference<Map<String, Object>>() {
                    });
                    String username = rs.getString("username");
                    Actor actor = username == null ? null
                            : new Actor(username, rs.getString("display_name"), rs.getString("avatar_url"));
                    String snippet = data.get("comment") instanceof String comment ? comment : snippet(rs.getString("target_text"));
                    return new Notification(rs.getLong("id"), type, actor, message(type, kind, data), snippet,
                            link(type, rs.getString("review_link"), username, rs.getString("me")),
                            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("read_at") != null);
                })
                .list();
        Instant next = items.size() == limit ? items.getLast().createdAt() : null;
        return new Page(items, next, unread(userId));
    }

    private static String message(String type, String kind, Map<String, Object> data) {
        String what = switch (kind == null ? "" : kind) {
            case "post" -> "seu post";
            case "review" -> "sua review";
            case "comment" -> "seu comentário";
            default -> "sua publicação";
        };
        return switch (type) {
            case "like" -> "curtiu " + what;
            case "comment" -> "comentou " + ("review".equals(kind) ? "na sua review" : "no seu post");
            case "reply" -> "respondeu seu comentário";
            case "follow" -> "começou a seguir você";
            case "rank_up" -> "founder".equals(data.get("kind"))
                    ? "Você é um dos Fundadores da GearHub!"
                    : "Você subiu para " + data.get("rank") + "!";
            default -> "";
        };
    }

    // Post não tem página própria: o link leva ao perfil de quem recebeu, onde ele aparece
    private static String link(String type, String reviewLink, String actor, String me) {
        return switch (type) {
            case "follow" -> "/profile/" + actor;
            case "rank_up" -> "/niveis/" + me;
            default -> reviewLink != null ? reviewLink : "/profile/" + me;
        };
    }

    @Transactional(readOnly = true)
    public int unread(UUID userId) {
        return jdbc.sql("""
                SELECT count(*) FROM notifications n LEFT JOIN users a ON a.id = n.actor_id
                WHERE n.user_id = :user AND n.read_at IS NULL AND (n.actor_id IS NULL OR a.deleted_at IS NULL)
                """).param("user", userId).query(Integer.class).single();
    }

    @Transactional
    public void markAllRead(UUID userId) {
        jdbc.sql("UPDATE notifications SET read_at = now() WHERE user_id = :user AND read_at IS NULL")
                .param("user", userId).update();
    }

    // ---------- Preferências ----------

    @Transactional(readOnly = true)
    public Settings settings(UUID userId) {
        return jdbc.sql("SELECT likes, comments, follows, rank_ups FROM notification_settings WHERE user_id = :user")
                .param("user", userId)
                .query((rs, row) -> new Settings(rs.getBoolean("likes"), rs.getBoolean("comments"),
                        rs.getBoolean("follows"), rs.getBoolean("rank_ups")))
                .optional()
                .orElse(new Settings(true, true, true, true));
    }

    @Transactional
    public Settings saveSettings(UUID userId, Settings settings) {
        jdbc.sql("""
                INSERT INTO notification_settings (user_id, likes, comments, follows, rank_ups)
                VALUES (:user, :likes, :comments, :follows, :rankUps)
                ON CONFLICT (user_id) DO UPDATE SET likes = excluded.likes, comments = excluded.comments,
                    follows = excluded.follows, rank_ups = excluded.rank_ups, updated_at = now()
                """)
                .param("user", userId).param("likes", settings.likes()).param("comments", settings.comments())
                .param("follows", settings.follows()).param("rankUps", settings.rankUps())
                .update();
        return settings;
    }
}
