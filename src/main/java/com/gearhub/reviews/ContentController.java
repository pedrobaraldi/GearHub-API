package com.gearhub.reviews;

import com.gearhub.auth.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Conteúdo", description = "Feed, posts, reviews, curtidas, salvos e comentários")
public class ContentController {

    private static final String KIND = "{kind:posts|reviews}";

    private final ContentService content;
    private final InteractionService interactions;
    private final ContentImageService images;

    public ContentController(ContentService content, InteractionService interactions, ContentImageService images) {
        this.content = content;
        this.interactions = interactions;
        this.images = images;
    }

    @GetMapping("/search")
    @Operation(summary = "Busca produtos, pessoas e publicações")
    public ContentResponses.SearchResults search(
            @AuthenticationPrincipal CurrentUser current,
            @RequestParam String q) {
        return content.search(q, idOf(current));
    }

    @GetMapping("/post-topics")
    @Operation(summary = "Lista os tópicos disponíveis para posts")
    public List<ContentResponses.PostTopic> postTopics() {
        return content.postTopics();
    }

    @PostMapping(path = "/uploads/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Envia uma imagem de post ou review (JPG, PNG ou WebP, até 2 MB)",
            security = @SecurityRequirement(name = "session"))
    public ContentResponses.ImageUrl uploadImage(
            @AuthenticationPrincipal CurrentUser current,
            @RequestPart("file") MultipartFile file) {
        return new ContentResponses.ImageUrl(images.store(current.id(), file));
    }

    private static UUID idOf(CurrentUser current) {
        return current == null ? null : current.id();
    }

    @GetMapping("/feed")
    @Operation(summary = "Lista posts e reviews, do mais novo para o mais antigo. `product` traz só as reviews desse produto e das categorias relacionadas")
    public ContentResponses.FeedPage feed(
            @AuthenticationPrincipal CurrentUser current,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String author,
            @RequestParam(required = false) String product,
            @Parameter(description = "Slug do jogo: posts sobre ele e reviews dele") @RequestParam(required = false) String game,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Instant before,
            @RequestParam(defaultValue = "10") int size) {
        String text = q == null || q.isBlank() ? null : ContentService.likePattern(q);
        return content.feed(new ContentService.FeedQuery(category, author, null, before, text, size, product, game), idOf(current));
    }

    @GetMapping("/me/saved")
    @Operation(summary = "Lista as publicações salvas", security = @SecurityRequirement(name = "session"))
    public ContentResponses.FeedPage saved(
            @AuthenticationPrincipal CurrentUser current,
            @RequestParam(required = false) Instant before,
            @RequestParam(defaultValue = "20") int size) {
        return content.feed(new ContentService.FeedQuery(null, null, current.id(), before, null, size, null, null), current.id());
    }

    @GetMapping("/products")
    @Operation(summary = "Lista os produtos, com filtro opcional por categoria")
    public List<ContentResponses.ProductSummary> products(@RequestParam(required = false) String category) {
        return content.products(category);
    }

    @GetMapping("/hall")
    @Operation(summary = "Lista os destaques: reviewers com mais XP, setups com foto e produtos mais bem avaliados")
    public ContentResponses.Hall hall() {
        return content.hall();
    }

    @GetMapping("/products/{slug}")
    @Operation(summary = "Busca um produto pelo slug")
    public ContentResponses.ProductDetail product(@PathVariable String slug) {
        return content.product(slug);
    }

    @PostMapping("/posts")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Publica um post", security = @SecurityRequirement(name = "session"))
    public Map<String, UUID> createPost(
            @AuthenticationPrincipal CurrentUser current,
            @Valid @RequestBody ContentRequests.SavePost request) {
        return Map.of("id", content.createPost(current.id(), request));
    }

    @GetMapping("/posts/{id}/edit")
    @Operation(summary = "Dados do próprio post para edição", security = @SecurityRequirement(name = "session"))
    public ContentResponses.PostDraft postDraft(@AuthenticationPrincipal CurrentUser current, @PathVariable UUID id) {
        return content.postDraft(current.id(), id);
    }

    @PatchMapping("/posts/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Edita o próprio post", security = @SecurityRequirement(name = "session"))
    @ApiResponse(responseCode = "403", description = "A publicação é de outra pessoa")
    public void updatePost(
            @AuthenticationPrincipal CurrentUser current,
            @PathVariable UUID id,
            @Valid @RequestBody ContentRequests.SavePost request) {
        content.updatePost(current.id(), id, request);
    }

    @PostMapping("/reviews")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Publica uma review", security = @SecurityRequirement(name = "session"))
    @ApiResponse(responseCode = "400", description = "Notas ou campos obrigatórios faltando")
    @ApiResponse(responseCode = "409", description = "Já existe uma review sua desse produto")
    public Map<String, UUID> createReview(
            @AuthenticationPrincipal CurrentUser current,
            @Valid @RequestBody ContentRequests.CreateReview request) {
        return Map.of("id", content.createReview(current.id(), request));
    }

    @GetMapping("/reviews/{id}/edit")
    @Operation(summary = "Dados da própria review para edição", security = @SecurityRequirement(name = "session"))
    public ContentResponses.ReviewDraft reviewDraft(@AuthenticationPrincipal CurrentUser current, @PathVariable UUID id) {
        return content.reviewDraft(current.id(), id);
    }

    @PatchMapping("/reviews/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Edita a própria review", security = @SecurityRequirement(name = "session"))
    @ApiResponse(responseCode = "403", description = "A publicação é de outra pessoa")
    public void updateReview(
            @AuthenticationPrincipal CurrentUser current,
            @PathVariable UUID id,
            @Valid @RequestBody ContentRequests.UpdateReview request) {
        content.updateReview(current.id(), id, request);
    }

    @DeleteMapping("/" + KIND + "/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Apaga a própria publicação", security = @SecurityRequirement(name = "session"))
    @ApiResponse(responseCode = "403", description = "A publicação é de outra pessoa")
    public void delete(@AuthenticationPrincipal CurrentUser current, @PathVariable String kind, @PathVariable UUID id) {
        content.delete(ContentKind.fromPath(kind), current.id(), id);
    }

    @PutMapping("/" + KIND + "/{id}/like")
    @Operation(summary = "Curte a publicação", security = @SecurityRequirement(name = "session"))
    public ContentResponses.LikeState like(
            @AuthenticationPrincipal CurrentUser current, @PathVariable String kind, @PathVariable UUID id) {
        return interactions.setLike(ContentKind.fromPath(kind), id, current.id(), true);
    }

    @DeleteMapping("/" + KIND + "/{id}/like")
    @Operation(summary = "Remove a curtida", security = @SecurityRequirement(name = "session"))
    public ContentResponses.LikeState unlike(
            @AuthenticationPrincipal CurrentUser current, @PathVariable String kind, @PathVariable UUID id) {
        return interactions.setLike(ContentKind.fromPath(kind), id, current.id(), false);
    }

    @PutMapping("/" + KIND + "/{id}/save")
    @Operation(summary = "Salva a publicação", security = @SecurityRequirement(name = "session"))
    public ContentResponses.SaveState save(
            @AuthenticationPrincipal CurrentUser current, @PathVariable String kind, @PathVariable UUID id) {
        return interactions.setSaved(ContentKind.fromPath(kind), id, current.id(), true);
    }

    @DeleteMapping("/" + KIND + "/{id}/save")
    @Operation(summary = "Tira dos salvos", security = @SecurityRequirement(name = "session"))
    public ContentResponses.SaveState unsave(
            @AuthenticationPrincipal CurrentUser current, @PathVariable String kind, @PathVariable UUID id) {
        return interactions.setSaved(ContentKind.fromPath(kind), id, current.id(), false);
    }

    @GetMapping("/" + KIND + "/{id}/comments")
    @Operation(summary = "Lista os comentários")
    public List<ContentResponses.CommentItem> comments(
            @AuthenticationPrincipal CurrentUser current, @PathVariable String kind, @PathVariable UUID id) {
        return interactions.comments(ContentKind.fromPath(kind), id, idOf(current));
    }

    @PostMapping("/" + KIND + "/{id}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Comenta", security = @SecurityRequirement(name = "session"))
    public Map<String, UUID> comment(
            @AuthenticationPrincipal CurrentUser current,
            @PathVariable String kind,
            @PathVariable UUID id,
            @Valid @RequestBody ContentRequests.SaveComment request) {
        return Map.of("id", interactions.addComment(ContentKind.fromPath(kind), id, current.id(), request.body(), request.parentId()));
    }

    @DeleteMapping("/comments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Apaga o próprio comentário", security = @SecurityRequirement(name = "session"))
    public void deleteComment(@AuthenticationPrincipal CurrentUser current, @PathVariable UUID id) {
        interactions.deleteComment(id, current.id(), "admin".equals(current.role()));
    }

    @PutMapping("/comments/{id}/like")
    @Operation(summary = "Curte o comentário", security = @SecurityRequirement(name = "session"))
    public ContentResponses.LikeState likeComment(@AuthenticationPrincipal CurrentUser current, @PathVariable UUID id) {
        return interactions.setCommentLike(id, current.id(), true);
    }

    @DeleteMapping("/comments/{id}/like")
    @Operation(summary = "Remove a curtida do comentário", security = @SecurityRequirement(name = "session"))
    public ContentResponses.LikeState unlikeComment(@AuthenticationPrincipal CurrentUser current, @PathVariable UUID id) {
        return interactions.setCommentLike(id, current.id(), false);
    }
}
