package com.gearhub.reviews;

import com.gearhub.admin.AuditService;
import com.gearhub.config.FieldValidationException;
import com.gearhub.reviews.ContentResponses.PostDraft;
import com.gearhub.reviews.ContentResponses.PostTopic;
import java.sql.Types;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Posts: publicar, editar, apagar e abrir para edição. Tags saem do campo próprio e das #hashtags do texto. */
@Service
public class PostService {

    private static final int MAX_TAGS = 10;
    private static final Pattern TAG = Pattern.compile("[\\p{L}\\p{N}_-]{1,30}");
    private static final Pattern HASHTAG = Pattern.compile("#(" + TAG.pattern() + ")");

    private final JdbcClient jdbc;
    private final Publications publications;
    private final AuditService audit;

    public PostService(JdbcClient jdbc, Publications publications, AuditService audit) {
        this.jdbc = jdbc;
        this.publications = publications;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<PostTopic> topics() {
        return jdbc.sql("SELECT slug, name, emoji FROM post_topics ORDER BY position")
                .query((rs, row) -> new PostTopic(rs.getString("slug"), rs.getString("name"), rs.getString("emoji")))
                .list();
    }

    @Transactional
    public UUID create(UUID userId, ContentRequests.SavePost request) {
        UUID postId = jdbc.sql("""
                INSERT INTO posts (user_id, topic_id, body, images, tags, game_id)
                VALUES (:user, :topic, :body, cast(:images AS jsonb), cast(:tags AS jsonb), :game) RETURNING id
                """)
                .param("user", userId)
                .param("game", gameIdOf(request.game()), Types.INTEGER)
                .param("topic", topicIdOf(request.topic()), Types.INTEGER)
                .param("body", request.body().strip())
                .param("images", publications.toJson(publications.validImages(request.images(), List.of())))
                .param("tags", publications.toJson(tagsOf(request)))
                .query(UUID.class).single();
        audit.record(userId, "post.create", "post", postId, Map.of("text", AuditService.snippet(request.body())));
        return postId;
    }

    @Transactional
    public void update(UUID userId, UUID postId, ContentRequests.SavePost request) {
        publications.requireOwner(ContentKind.POST, postId, userId);
        // Versão anterior guardada na auditoria: nenhuma edição apaga o que foi publicado antes
        Map<String, Object> before = publications.snapshot(ContentKind.POST, postId);
        List<String> currentImages = publications.parseList(jdbc.sql("SELECT images::text FROM posts WHERE id = :id")
                .param("id", postId).query(String.class).single());
        jdbc.sql("""
                UPDATE posts SET topic_id = :topic, body = :body, images = cast(:images AS jsonb),
                       tags = cast(:tags AS jsonb), game_id = :game, updated_at = now()
                WHERE id = :id
                """)
                .param("game", gameIdOf(request.game()), Types.INTEGER)
                .param("topic", topicIdOf(request.topic()), Types.INTEGER)
                .param("body", request.body().strip())
                .param("images", publications.toJson(publications.validImages(request.images(), currentImages)))
                .param("tags", publications.toJson(tagsOf(request)))
                .param("id", postId)
                .update();
        audit.record(userId, "post.update", "post", postId,
                Map.of("before", before, "after", publications.snapshot(ContentKind.POST, postId)));
    }

    @Transactional
    public void delete(UUID userId, UUID postId) {
        publications.markDeleted(ContentKind.POST, userId, postId);
    }

    @Transactional(readOnly = true)
    public PostDraft draft(UUID userId, UUID postId) {
        publications.requireOwner(ContentKind.POST, postId, userId);
        return jdbc.sql("""
                SELECT p.body, t.slug, p.images::text AS images, p.tags::text AS tags,
                       g.slug AS game_slug, g.name AS game_name, g.cover_url AS game_cover FROM posts p
                LEFT JOIN post_topics t ON t.id = p.topic_id
                LEFT JOIN catalog_games g ON g.igdb_id = p.game_id WHERE p.id = :id
                """)
                .param("id", postId)
                .query((rs, row) -> {
                    // Hashtags continuam no texto: o campo de tags só mostra as demais
                    Set<String> inBody = hashtags(rs.getString("body"));
                    List<String> tags = publications.parseList(rs.getString("tags")).stream()
                            .filter(tag -> !inBody.contains(tag)).toList();
                    return new PostDraft(rs.getString("body"), rs.getString("slug"), tags,
                            publications.parseList(rs.getString("images")), Publications.gameRef(rs));
                })
                .single();
    }

    private Integer gameIdOf(String slug) {
        return slug == null || slug.isBlank() ? null : publications.catalogGame(slug).igdbId();
    }

    private Integer topicIdOf(String slug) {
        if (slug == null || slug.isBlank()) {
            return null;
        }
        return jdbc.sql("SELECT id FROM post_topics WHERE slug = :slug")
                .param("slug", slug)
                .query(Integer.class).optional()
                .orElseThrow(() -> new FieldValidationException(Map.of("topic", "Tópico não encontrado")));
    }

    private static Set<String> hashtags(String body) {
        Set<String> found = new LinkedHashSet<>();
        Matcher matcher = HASHTAG.matcher(body);
        while (matcher.find()) {
            found.add(matcher.group(1).toLowerCase());
        }
        return found;
    }

    private static List<String> tagsOf(ContentRequests.SavePost request) {
        Set<String> tags = new LinkedHashSet<>();
        if (request.tags() != null) {
            for (String tag : request.tags()) {
                String clean = tag == null ? "" : tag.strip().replaceFirst("^#", "").toLowerCase();
                if (clean.isEmpty()) {
                    continue;
                }
                if (!TAG.matcher(clean).matches()) {
                    throw new FieldValidationException(Map.of("tags", "Use só letras, números, - e _ nas tags"));
                }
                tags.add(clean);
            }
        }
        tags.addAll(hashtags(request.body()));
        return tags.stream().limit(MAX_TAGS).toList();
    }
}
