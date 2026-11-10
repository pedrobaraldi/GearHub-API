package com.gearhub.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import java.sql.Timestamp;
import java.sql.Types;
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
 * Auditoria: registra o que cada pessoa faz no site (publicar, editar, apagar, comentar, denunciar, entrar,
 * mudar a conta) e o que os admins fazem. É a base do painel de admin para ver o histórico de um perfil.
 * Grava na mesma transação da ação: se a ação falhar, o registro também não fica.
 */
@Service
public class AuditService {

    private static final int SNIPPET = 120;
    private static final int MAX_PAGE = 100;

    @Schema(description = "Uma ação registrada")
    public record Event(long id, @Schema(example = "post.create") String action, String targetKind, UUID targetId,
            Map<String, Object> data, Instant createdAt) {
    }

    public record Page(List<Event> items, Instant nextCursor) {
    }

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public AuditService(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void record(UUID actorId, String action, String targetKind, UUID targetId, Map<String, Object> data) {
        jdbc.sql("""
                INSERT INTO audit_events (actor_id, action, target_kind, target_id, data)
                VALUES (:actor, :action, :kind, :target, cast(:data AS jsonb))
                """)
                .param("actor", actorId, Types.OTHER).param("action", action)
                .param("kind", targetKind, Types.VARCHAR).param("target", targetId, Types.OTHER)
                .param("data", json.writeValueAsString(data == null ? Map.of() : data))
                .update();
    }

    public void record(UUID actorId, String action) {
        record(actorId, action, null, null, Map.of());
    }

    /** Trecho curto do texto publicado, para o histórico mostrar do que se trata. */
    public static String snippet(String text) {
        if (text == null) {
            return "";
        }
        String clean = text.strip().replaceAll("\\s+", " ");
        return clean.length() <= SNIPPET ? clean : clean.substring(0, SNIPPET - 1) + "…";
    }

    @Transactional(readOnly = true)
    public Page activity(UUID actorId, Instant before, int size) {
        int limit = Math.max(1, Math.min(size, MAX_PAGE));
        List<Event> items = jdbc.sql("""
                SELECT id, action, target_kind, target_id, data::text AS data, created_at FROM audit_events
                WHERE actor_id = :actor AND (CAST(:before AS timestamptz) IS NULL OR created_at < :before)
                ORDER BY created_at DESC, id DESC LIMIT :limit
                """)
                .param("actor", actorId)
                .param("before", before == null ? null : Timestamp.from(before), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("limit", limit)
                .query((rs, row) -> new Event(rs.getLong("id"), rs.getString("action"), rs.getString("target_kind"),
                        rs.getObject("target_id", UUID.class),
                        json.readValue(rs.getString("data"), new TypeReference<Map<String, Object>>() {
                        }),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
        return new Page(items, items.size() == limit ? items.getLast().createdAt() : null);
    }
}
