package com.gearhub.users;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Eventos de progressão ainda não vistos: subida de rank ou desbloqueio do Fundador.
 * O banco registra cada evento (V21); o site mostra a animação uma vez.
 */
@Service
public class RankUpService {

    @Schema(description = "Evento pendente. O Fundador vem antes; várias subidas de rank seguidas viram uma só")
    public record RankUp(
            @Schema(description = "Id do evento mais recente desse tipo, usado para marcar como visto") long id,
            @Schema(description = "rank ou founder", example = "rank") String kind,
            @Schema(description = "Rank antes da primeira subida; nulo no Fundador", example = "Prata") String fromRank,
            @Schema(example = "Cobalto") String toRank,
            int level,
            @Schema(description = "Só no Fundador: a ordem de cadastro, de 1 a 100") Integer founderNumber) {
    }

    private final JdbcClient jdbc;

    public RankUpService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Optional<RankUp> pending(UUID userId) {
        Optional<RankUp> founder = jdbc.sql("""
                SELECT r.id, r.kind, NULL AS from_rank, r.to_rank, u.level, u.founder_number
                FROM rank_ups r JOIN users u ON u.id = r.user_id
                WHERE r.user_id = :user AND r.kind = 'founder' AND r.seen_at IS NULL
                ORDER BY r.id LIMIT 1
                """)
                .param("user", userId)
                .query(RankUp.class)
                .optional();
        if (founder.isPresent()) {
            return founder;
        }
        return jdbc.sql("""
                SELECT max(r.id) AS id, 'rank' AS kind,
                       (array_agg(r.from_rank ORDER BY r.id))[1] AS from_rank,
                       (array_agg(r.to_rank ORDER BY r.id DESC))[1] AS to_rank,
                       (SELECT level FROM users WHERE id = :user) AS level,
                       NULL::integer AS founder_number
                FROM rank_ups r
                WHERE r.user_id = :user AND r.kind = 'rank' AND r.seen_at IS NULL
                HAVING count(*) > 0
                """)
                .param("user", userId)
                .query(RankUp.class)
                .optional();
    }

    /** Marca como visto o evento informado e os anteriores do mesmo tipo, da mesma pessoa. */
    @Transactional
    public void markSeen(UUID userId, long id) {
        jdbc.sql("""
                UPDATE rank_ups SET seen_at = now()
                WHERE user_id = :user AND seen_at IS NULL AND id <= :id
                  AND kind = (SELECT kind FROM rank_ups WHERE id = :id AND user_id = :user)
                """)
                .param("user", userId)
                .param("id", id)
                .update();
    }
}
