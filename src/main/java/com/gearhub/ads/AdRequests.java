package com.gearhub.ads;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Pedidos do módulo de publicidade (painel de admin). */
public final class AdRequests {

    private AdRequests() {
    }

    @Schema(description = "Espaço de anúncio. O site acha o espaço pela chave (slotKey)")
    public record SaveSlot(
            @Schema(example = "feed_inline") @NotBlank @Pattern(regexp = "^[a-z0-9][a-z0-9_]{1,59}$", message = "Use letras minúsculas, números e _") String slotKey,
            @NotBlank @Size(min = 2, max = 80) String name,
            @Size(max = 500) String description,
            @Schema(example = "feed") @NotBlank @Size(max = 60) String page,
            @Size(max = 60) String position,
            @Schema(allowableValues = {"banner", "panel", "promo_card", "brand", "institutional"}) @NotBlank String format,
            @Min(1) @Max(4000) Integer width,
            @Min(1) @Max(4000) Integer height,
            @Schema(example = "16:9") @Pattern(regexp = "^$|^[0-9]{1,2}:[0-9]{1,2}$", message = "Use o formato 16:9") String aspectRatio,
            @Min(10) @Max(1000) Integer textLimit,
            @Min(1) @Max(10) Integer maxItems,
            @Min(0) @Max(100) Integer priority,
            Boolean rotationEnabled,
            @Schema(allowableValues = {"active", "inactive"}) String status) {
    }

    @Schema(description = "Anunciante. O CPF/CNPJ fica criptografado")
    public record SaveAdvertiser(
            @NotBlank @Size(min = 2, max = 80) String displayName,
            @Size(max = 160) String legalName,
            @Schema(description = "CPF ou CNPJ, com ou sem pontuação. Vazio mantém o salvo") @Size(max = 20) String documentNumber,
            @Size(max = 120) String contactName,
            @Email @Size(max = 254) String email,
            @Size(max = 20) String phone,
            @Schema(description = "Imagem enviada em /api/v1/admin/ads/images") String logoUrl,
            @Pattern(regexp = "^$|^https://\\S+$", message = "Use um endereço começando com https://") @Size(max = 300) String websiteUrl,
            @Size(max = 10) List<@Pattern(regexp = "^https://\\S+$", message = "Use links começando com https://") @Size(max = 300) String> socialLinks,
            @Size(max = 2000) String notes,
            @Schema(allowableValues = {"active", "inactive"}) String status) {
        @Override
        public String toString() {
            return "SaveAdvertiser[]";
        }
    }

    @Schema(description = "Campanha. Editar uma campanha aprovada a devolve para aprovação")
    public record SaveCampaign(
            @NotNull String advertiserId,
            @NotBlank @Size(min = 2, max = 120) String name,
            @Size(max = 2000) String description,
            @NotNull Instant startAt,
            @NotNull Instant endAt,
            @Min(0) @Max(100) Integer priority,
            @Schema(allowableValues = {"fixed", "cpm", "cpc", "package", "exclusive"}) String commercialModel,
            @DecimalMin("0") BigDecimal commercialValue,
            @Positive Integer impressionLimit,
            @Schema(description = "Chaves dos espaços em que a campanha aparece") @Size(max = 20) List<String> slotKeys) {
    }

    @Schema(description = "Criativo: o conteúdo do anúncio, só por campos")
    public record SaveCreative(
            @Size(max = 120) String title,
            @Size(max = 1000) String description,
            String imageUrl,
            String mobileImageUrl,
            String logoUrl,
            @NotBlank @Pattern(regexp = "^https://\\S+$", message = "Use um link de destino começando com https://") @Size(max = 500) String destinationUrl,
            @Size(max = 30) String ctaText,
            @Size(max = 200) String altText,
            @Schema(allowableValues = {"active", "inactive"}) String status) {
    }

    @Schema(description = "Ação sobre a campanha")
    public record CampaignAction(
            @Schema(allowableValues = {"submit", "approve", "reject", "pause", "resume"}) @NotBlank String action,
            @Schema(description = "Obrigatória para reprovar") @Size(max = 1000) String note) {
    }

    @Schema(description = "Evento de impressão enviado pelo site quando o anúncio aparece na tela")
    public record Impression(
            @NotBlank @Size(max = 60) String slotKey,
            @NotNull String campaignId,
            @NotNull String creativeId,
            @NotBlank @Size(max = 64) String signature,
            @Schema(description = "Identificador aleatório da sessão no navegador") @NotBlank @Size(min = 8, max = 64) String sessionId) {
    }
}
