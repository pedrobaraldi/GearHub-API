package com.gearhub.users;

import com.gearhub.notifications.NotificationService;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FollowService {

    public record FollowCounts(int followers, int following, boolean followedByMe) {
    }

    private final NotificationService notifications;
    private final JdbcClient jdbc;
    private final UserRepository users;

    public FollowService(JdbcClient jdbc, UserRepository users, NotificationService notifications) {
        this.notifications = notifications;
        this.jdbc = jdbc;
        this.users = users;
    }

    @Transactional
    public FollowCounts setFollowing(UUID followerId, String username, boolean following) {
        User target = users.findByUsernameAndDeletedAtIsNull(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuário não encontrado"));
        if (target.getId().equals(followerId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Você não pode seguir a si mesmo");
        }
        String change = following
                ? "INSERT INTO follows (follower_id, followed_id) VALUES (:follower, :followed) ON CONFLICT DO NOTHING"
                : "DELETE FROM follows WHERE follower_id = :follower AND followed_id = :followed";
        jdbc.sql(change).param("follower", followerId).param("followed", target.getId()).update();
        notifications.followed(target.getId(), followerId, following);
        return counts(target.getId(), followerId);
    }

    @Transactional(readOnly = true)
    public FollowCounts counts(UUID userId, UUID viewerId) {
        int followers = jdbc.sql("SELECT count(*) FROM follows WHERE followed_id = :id")
                .param("id", userId).query(Integer.class).single();
        int following = jdbc.sql("SELECT count(*) FROM follows WHERE follower_id = :id")
                .param("id", userId).query(Integer.class).single();
        boolean followedByMe = viewerId != null && jdbc.sql(
                "SELECT count(*) FROM follows WHERE follower_id = :viewer AND followed_id = :id")
                .param("viewer", viewerId).param("id", userId).query(Integer.class).single() > 0;
        return new FollowCounts(followers, following, followedByMe);
    }
}
