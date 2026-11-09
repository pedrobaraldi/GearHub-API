package com.gearhub.users;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Subidas de rank ainda não vistas. O banco registra cada subida (V21); o site mostra a animação uma vez. */
@Service
public class RankUpService {

    @Schema(description = "Subida pendente. Várias seguidas viram uma só: do rank antes da primeira ao rank atual")
    public record RankUp(
            @Schema(description = "Id da subida mais recente, usado para marcar como vista") long id,
            @Schema(example = "Prata") String fromRank,
            @Schema(example = "Cobalto") String toRank,
            int level) {
    }

    private final JdbcClient jdbc;

    public RankUpService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Optional<RankUp> pending(UUID userId) {
        return jdbc.sql("""
                SELECT max(r.id) AS id,
                       (array_agg(r.from_rank ORDER BY r.id))[1] AS from_rank,
                       (array_agg(r.to_rank ORDER BY r.id DESC))[1] AS to_rank,
                       (SELECT level FROM users WHERE id = :user) AS level
                FROM rank_ups r
                WHERE r.user_id = :user AND r.seen_at IS NULL
                HAVING count(*) > 0
                """)
                .param("user", userId)
                .query(RankUp.class)
                .optional();
    }

    /** Marca como vistas a subida informada e todas as anteriores da mesma pessoa. */
    @Transactional
    public void markSeen(UUID userId, long id) {
        jdbc.sql("UPDATE rank_ups SET seen_at = now() WHERE user_id = :user AND id <= :id AND seen_at IS NULL")
                .param("user", userId)
                .param("id", id)
                .update();
    }
}
