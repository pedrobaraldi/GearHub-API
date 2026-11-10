package com.gearhub.reviews;

import com.gearhub.config.FieldValidationException;
import com.gearhub.reviews.ContentResponses.CommentItem;
import com.gearhub.reviews.ContentResponses.LikeState;
import com.gearhub.reviews.ContentResponses.SaveState;
import java.sql.Types;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Curtidas, salvos e comentários. As operações são idempotentes: repetir não muda o resultado. */
@Service
public class InteractionService {

    private static final int MAX_COMMENTS = 200;

    private final JdbcClient jdbc;
    private final ContentService content;

    public InteractionService(JdbcClient jdbc, ContentService content) {
        this.jdbc = jdbc;
        this.content = content;
    }

    @Transactional
    public LikeState setLike(ContentKind kind, UUID id, UUID userId, boolean liked) {
        content.ownerOf(kind, id);
        return toggleLike(kind.likesTable(), kind.foreignKey(), kind.table(), id, userId, liked);
    }

    @Transactional
    public LikeState setCommentLike(UUID commentId, UUID userId, boolean liked) {
        requireComment(commentId);
        return toggleLike("comment_likes", "comment_id", "comments", commentId, userId, liked);
    }

    private LikeState toggleLike(String likes, String foreignKey, String target, UUID id, UUID userId, boolean liked) {
        String change = liked
                ? "INSERT INTO " + likes + " (" + foreignKey + ", user_id) VALUES (:id, :user) ON CONFLICT DO NOTHING"
                : "DELETE FROM " + likes + " WHERE " + foreignKey + " = :id AND user_id = :user";
        int changed = jdbc.sql(change).param("id", id).param("user", userId).update();
        if (changed > 0) {
            jdbc.sql("UPDATE " + target + " SET like_count = GREATEST(like_count + :delta, 0) WHERE id = :id")
                    .param("delta", liked ? 1 : -1).param("id", id).update();
        }
        int count = jdbc.sql("SELECT like_count FROM " + target + " WHERE id = :id")
                .param("id", id).query(Integer.class).single();
        return new LikeState(liked, count);
    }

    @Transactional
    public SaveState setSaved(ContentKind kind, UUID id, UUID userId, boolean saved) {
        content.ownerOf(kind, id);
        String change = saved
                ? "INSERT INTO " + kind.savedTable() + " (" + kind.foreignKey() + ", user_id) VALUES (:id, :user) ON CONFLICT DO NOTHING"
                : "DELETE FROM " + kind.savedTable() + " WHERE " + kind.foreignKey() + " = :id AND user_id = :user";
        jdbc.sql(change).param("id", id).param("user", userId).update();
        return new SaveState(saved);
    }

    @Transactional(readOnly = true)
    public List<CommentItem> comments(ContentKind kind, UUID id, UUID viewerId) {
        content.ownerOf(kind, id);
        Set<UUID> liked = new HashSet<>();
        if (viewerId != null) {
            liked.addAll(jdbc.sql("""
                    SELECT l.comment_id FROM comment_likes l
                    JOIN comments c ON c.id = l.comment_id
                    WHERE l.user_id = :user AND c.%s = :id
                    """.formatted(kind.foreignKey()))
                    .param("user", viewerId).param("id", id).query(UUID.class).list());
        }
        return jdbc.sql("""
                SELECT c.id, c.user_id, c.body, c.like_count, c.created_at, c.parent_id,
                       t.display_name AS reply_to,
                       u.username, u.display_name, u.avatar_url, u.rank_name, u.level, u.is_pro,
                       CASE WHEN u.ring_style = 'founder' AND u.founder_number IS NOT NULL THEN 'founder'
                            WHEN EXISTS (SELECT 1 FROM ranks rk WHERE rk.name = u.ring_style AND rk.min_level <= u.level) THEN u.ring_style
                       END AS ring_style,
                       u.founder_number IS NOT NULL AS founder
                FROM comments c JOIN users u ON u.id = c.user_id
                LEFT JOIN users t ON t.id = c.reply_to_user_id
                WHERE c.%s = :id AND c.deleted_at IS NULL
                ORDER BY c.created_at LIMIT :limit
                """.formatted(kind.foreignKey()))
                .param("id", id).param("limit", MAX_COMMENTS)
                .query((rs, row) -> {
                    UUID commentId = rs.getObject("id", UUID.class);
                    return new CommentItem(commentId, ContentService.author(rs), rs.getString("body"),
                            rs.getTimestamp("created_at").toInstant(), rs.getInt("like_count"),
                            liked.contains(commentId), rs.getObject("user_id", UUID.class).equals(viewerId),
                            rs.getObject("parent_id", UUID.class), rs.getString("reply_to"));
                })
                .list();
    }

    @Transactional
    public UUID addComment(ContentKind kind, UUID id, UUID userId, String body, UUID parentId) {
        content.ownerOf(kind, id);
        ReplyTarget target = parentId == null ? new ReplyTarget(null, null) : replyTarget(kind, id, parentId);
        UUID commentId = jdbc.sql("INSERT INTO comments (" + kind.foreignKey()
                + ", user_id, body, parent_id, reply_to_user_id) VALUES (:id, :user, :body, :parent, :replyTo) RETURNING id")
                .param("id", id).param("user", userId).param("body", body.strip())
                .param("parent", target.root(), Types.OTHER).param("replyTo", target.mention(), Types.OTHER)
                .query(UUID.class).single();
        jdbc.sql("UPDATE " + kind.table() + " SET comment_count = comment_count + 1 WHERE id = :id")
                .param("id", id).update();
        return commentId;
    }

    private record ReplyTarget(UUID root, UUID mention) {
    }

    /** Resposta a uma resposta fica na conversa original e cita a pessoa. */
    private ReplyTarget replyTarget(ContentKind kind, UUID id, UUID parentId) {
        return jdbc.sql("""
                SELECT parent_id, user_id FROM comments
                WHERE id = :parent AND %s = :id AND deleted_at IS NULL
                """.formatted(kind.foreignKey()))
                .param("parent", parentId).param("id", id)
                .query((rs, row) -> {
                    UUID root = rs.getObject("parent_id", UUID.class);
                    return root == null ? new ReplyTarget(parentId, null)
                            : new ReplyTarget(root, rs.getObject("user_id", UUID.class));
                })
                .optional()
                .orElseThrow(() -> new FieldValidationException(Map.of("parentId", "Comentário não encontrado")));
    }

    @Transactional
    public void deleteComment(UUID commentId, UUID userId, boolean admin) {
        record Comment(UUID owner, UUID reviewId, UUID postId) {
        }
        Comment comment = jdbc.sql("SELECT user_id, review_id, post_id FROM comments WHERE id = :id AND deleted_at IS NULL")
                .param("id", commentId)
                .query((rs, row) -> new Comment(rs.getObject("user_id", UUID.class),
                        rs.getObject("review_id", UUID.class), rs.getObject("post_id", UUID.class)))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Comentário não encontrado"));
        if (!admin && !comment.owner().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Você só pode apagar os seus comentários");
        }
        // Apagar um comentário leva as respostas junto
        int removed = jdbc.sql("""
                UPDATE comments SET deleted_at = now()
                WHERE (id = :id OR parent_id = :id) AND deleted_at IS NULL
                """).param("id", commentId).update();

        ContentKind kind = comment.reviewId() != null ? ContentKind.REVIEW : ContentKind.POST;
        UUID parent = comment.reviewId() != null ? comment.reviewId() : comment.postId();
        jdbc.sql("UPDATE " + kind.table() + " SET comment_count = GREATEST(comment_count - :removed, 0) WHERE id = :id")
                .param("removed", removed).param("id", parent).update();
    }

    private void requireComment(UUID commentId) {
        boolean exists = jdbc.sql("SELECT count(*) FROM comments WHERE id = :id AND deleted_at IS NULL")
                .param("id", commentId).query(Long.class).single() > 0;
        if (!exists) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Comentário não encontrado");
        }
    }
}
