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
            boolean pro,
            @Schema(description = "Contorno da foto que vale agora; nulo é automático") String ringStyle,
            @Schema(description = "Se é um dos 100 primeiros (contorno RGB no automático)") boolean founder) {
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

    public record ProductSpec(String label, String value) {
    }

    @Schema(description = "Página de um produto")
    public record ProductDetail(UUID id, String slug, String name, String brand, String category, String imageUrl,
            String about, List<ProductSpec> specs, BigDecimal avgScore, int reviewCount, List<ProductScore> scores) {
    }

    @Schema(description = "Média de um critério entre as reviews do produto")
    public record ProductScore(String label, BigDecimal value) {
    }

    public record HallReviewer(String username, String displayName, String avatarUrl, long reviewCount, int xp, int level,
            String rankName) {
    }

    public record HallSetup(String username, String displayName, String avatarUrl, String photo) {
    }

    @Schema(description = "Destaques calculados com a atividade real da comunidade")
    public record Hall(List<HallReviewer> reviewers, List<HallSetup> setups, List<ProductSummary> products) {
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

    @Schema(description = "`parentId` agrupa as respostas; `replyTo` é o nome de quem foi respondido dentro da conversa")
    public record CommentItem(UUID id, Author author, String body, Instant createdAt, int likeCount,
            boolean likedByMe, boolean mine, UUID parentId, String replyTo) {
    }
}
