package com.gearhub.reviews;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ContentRequests {

    private ContentRequests() {
    }

    public record SavePost(
            @NotBlank(message = "Escreva o conteúdo do post")
            @Size(max = 1000, message = "O texto deve ter no máximo 1000 caracteres")
            String body,

            @Schema(description = "Slug do tópico. Opcional", example = "discussao")
            String topic,

            @Schema(description = "Tags sem #. Hashtags do texto entram automaticamente")
            @Size(max = 10, message = "Use no máximo 10 tags")
            List<@Size(max = 30, message = "Cada tag deve ter no máximo 30 caracteres") String> tags,

            @Schema(description = "Endereços devolvidos por POST /uploads/images")
            @Size(max = 10, message = "Envie no máximo 10 imagens")
            List<String> images) {
    }

    @Schema(description = "Informe o produto pela categoria, marca e modelo: se ele já existir, a review entra nele; senão, é criado. "
            + "productId continua aceito para ligar a um produto conhecido")
    public record CreateReview(
            UUID productId,

            @Schema(description = "Slug da categoria", example = "mouse")
            @Size(max = 40, message = "Escolha uma categoria válida")
            String category,

            @Schema(example = "Logitech")
            @Size(max = 60, message = "A marca deve ter no máximo 60 caracteres")
            String brand,

            @Schema(example = "G Pro X Superlight 2")
            @Size(max = 120, message = "O modelo deve ter no máximo 120 caracteres")
            String model,

            @Size(max = 120, message = "O título deve ter no máximo 120 caracteres")
            String title,

            @Size(max = 5000, message = "A avaliação deve ter no máximo 5000 caracteres")
            String body,

            @Schema(description = "Nota de 0 a 10 por id de critério")
            Map<String, BigDecimal> scores,

            @Schema(description = "Ficha técnica: valor por chave de campo da categoria")
            Map<String, String> specs,

            @Size(max = 10, message = "Liste no máximo 10 prós")
            List<@Size(max = 120, message = "Use no máximo 120 caracteres em cada pró") String> pros,

            @Size(max = 10, message = "Liste no máximo 10 contras")
            List<@Size(max = 120, message = "Use no máximo 120 caracteres em cada contra") String> cons,

            @Schema(description = "Endereços devolvidos por POST /uploads/images. Sem eles, usa a foto do produto")
            @Size(max = 10, message = "Envie no máximo 10 imagens")
            List<String> images) {
    }

    @Schema(description = "O produto de uma review não muda depois de publicada")
    public record UpdateReview(
            @Size(max = 120, message = "O título deve ter no máximo 120 caracteres")
            String title,

            @Size(max = 5000, message = "A avaliação deve ter no máximo 5000 caracteres")
            String body,

            Map<String, BigDecimal> scores,

            Map<String, String> specs,

            @Size(max = 10, message = "Liste no máximo 10 prós")
            List<@Size(max = 120, message = "Use no máximo 120 caracteres em cada pró") String> pros,

            @Size(max = 10, message = "Liste no máximo 10 contras")
            List<@Size(max = 120, message = "Use no máximo 120 caracteres em cada contra") String> cons,

            @Schema(description = "Endereços devolvidos por POST /uploads/images. Sem eles, usa a foto do produto")
            @Size(max = 10, message = "Envie no máximo 10 imagens")
            List<String> images) {
    }

    public record SaveComment(
            @NotBlank(message = "Escreva o comentário")
            @Size(max = 2000, message = "O comentário deve ter no máximo 2000 caracteres")
            String body,

            @Schema(description = "Comentário que está sendo respondido")
            UUID parentId) {
    }
}
