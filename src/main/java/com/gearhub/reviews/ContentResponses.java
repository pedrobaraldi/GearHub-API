package com.gearhub.reviews;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ContentResponses {

    private ContentResponses() {
    }

    public record Author(String username, String displayName, String avatarUrl, String rankName, int level,
            boolean pro) {
    }

    public record ProductRef(String slug, String name, String brand, String category, String imageUrl) {
    }

    public record Score(String label, BigDecimal value) {
    }

    public record Spec(String label, String value) {
    }

    @Schema(description = "Item do feed. Em `post`, os campos de produto e notas vêm vazios")
    public record FeedItem(
            @Schema(allowableValues = {"review", "post"}) String kind,
            UUID id,
            Author author,
            Instant createdAt,
            String title,
            String body,
            String category,
            int likeCount,
            int commentCount,
            ProductRef product,
            BigDecimal score,
            String coverUrl,
            List<Score> scores,
            List<Spec> specs,
            List<String> pros,
            List<String> cons,
            List<String> tags,
            List<String> images,
            @Schema(description = "Se quem está logado curtiu") boolean likedByMe,
            @Schema(description = "Se quem está logado salvou") boolean savedByMe,
            @Schema(description = "Se a publicação é de quem está logado") boolean mine) {

        FeedItem withViewer(boolean liked, boolean saved, boolean own) {
            return new FeedItem(kind, id, author, createdAt, title, body, category, likeCount, commentCount, product,
                    score, coverUrl, scores, specs, pros, cons, tags, images, liked, saved, own);
        }
    }

    @Schema(description = "`nextCursor` vazio indica o fim da lista")
    public record FeedPage(List<FeedItem> items, Instant nextCursor) {
    }

    public record ProductSummary(UUID id, String slug, String name, String brand, String category, String imageUrl,
            BigDecimal avgScore, int reviewCount) {
    }

    @Schema(description = "Dados de um post para o formulário de edição")
    public record PostDraft(String body, String topic, List<String> tags, List<String> images) {
    }

    @Schema(description = "Dados de uma review para o formulário de edição")
    public record ReviewDraft(
            UUID productId,
            String productName,
            String category,
            String title,
            String body,
            Map<String, BigDecimal> scores,
            Map<String, String> specs,
            List<String> pros,
            List<String> cons,
            List<String> images) {
    }

    @Schema(description = "Resultado da busca global")
    public record SearchResults(List<ProductSummary> products, List<Author> people, List<FeedItem> publications) {
    }

    @Schema(description = "Tópico de um post")
    public record PostTopic(String slug, String name, String emoji) {
    }

    public record ImageUrl(String url) {
    }

    public record LikeState(boolean liked, int likeCount) {
    }

    public record SaveState(boolean saved) {
    }

    public record CommentItem(UUID id, Author author, String body, Instant createdAt, int likeCount,
            boolean likedByMe, boolean mine) {
    }
}
