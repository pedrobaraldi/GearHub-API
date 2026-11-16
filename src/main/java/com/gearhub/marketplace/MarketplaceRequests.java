package com.gearhub.marketplace;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/** Pedidos do marketplace. Campos vazios na solicitação são permitidos no rascunho e cobrados no envio. */
public final class MarketplaceRequests {

    private MarketplaceRequests() {
    }

    @Schema(description = "Solicitação de loja parceira. Pode ser salva aos poucos como rascunho")
    public record SaveApplication(
            @Size(max = 120, message = "Use no máximo 120 caracteres") String responsibleName,
            @Schema(example = "123.456.789-09") @Size(max = 20, message = "CPF inválido") String cpf,
            @Email(message = "E-mail inválido") @Size(max = 254, message = "E-mail muito longo") String contactEmail,
            @Schema(example = "(11) 98765-4321") @Size(max = 20, message = "Telefone inválido") String phone,
            @Size(max = 80, message = "Use no máximo 80 caracteres") String roleInCompany,
            @Schema(example = "12.345.678/0001-95") @Size(max = 20, message = "CNPJ inválido") String cnpj,
            @Size(max = 160, message = "Use no máximo 160 caracteres") String legalName,
            @Size(max = 80, message = "Use no máximo 80 caracteres") String tradeName,
            @Size(max = 80, message = "Use no máximo 80 caracteres") String segment,
            @Size(max = 300, message = "Use no máximo 300 caracteres") String address,
            @Pattern(regexp = "^$|^https?://\\S+$", message = "Use um endereço começando com https://")
            @Size(max = 200, message = "Use no máximo 200 caracteres") String website,
            @Size(max = 2000, message = "Use no máximo 2000 caracteres") String description) {
        @Override
        public String toString() {
            return "SaveApplication[]";
        }
    }

    @Schema(description = "Perfil público da loja")
    public record SaveStore(
            @NotBlank(message = "Informe o nome da loja") @Size(min = 2, max = 80, message = "Use de 2 a 80 caracteres") String name,
            @Size(max = 2000, message = "Use no máximo 2000 caracteres") String description,
            @Schema(description = "Imagem enviada em /api/v1/marketplace/my-store/images") String logoUrl,
            String bannerUrl,
            @Size(max = 80, message = "Use no máximo 80 caracteres") String segment,
            @Pattern(regexp = "^$|^https://\\S+$", message = "Use um endereço começando com https://")
            @Size(max = 200, message = "Use no máximo 200 caracteres") String website,
            @Pattern(regexp = "^$|^@?[A-Za-z0-9._]{1,30}$", message = "Informe um usuário válido do Instagram") String instagram) {
    }

    @Schema(description = "Produto da loja. O preço é só informativo: a venda acontece no site da loja")
    public record SaveProduct(
            @NotBlank(message = "Informe o nome do produto") @Size(min = 2, max = 120, message = "Use de 2 a 120 caracteres") String name,
            @Size(max = 3000, message = "Use no máximo 3000 caracteres") String description,
            @Size(max = 10, message = "Envie no máximo 10 imagens") List<String> images,
            @Schema(description = "Slug de uma categoria do GearHub", example = "mouse") @Size(max = 40) String category,
            @DecimalMin(value = "0", message = "O preço não pode ser negativo") BigDecimal price,
            @Schema(allowableValues = {"in_stock", "out_of_stock", "preorder"}) String availability,
            @NotBlank(message = "Informe o link de compra na sua loja")
            @Pattern(regexp = "^https://\\S+$", message = "Use um link de compra começando com https://")
            @Size(max = 500, message = "Use no máximo 500 caracteres") String purchaseUrl) {
    }

    public record ProductStatus(
            @Schema(allowableValues = {"active", "paused", "archived"}) @NotBlank String status) {
    }

    @Schema(description = "Decisão do admin sobre a solicitação")
    public record ReviewApplication(
            @Schema(allowableValues = {"in_review", "awaiting_info", "approved", "rejected"}) @NotBlank String status,
            @Schema(description = "Obrigatória para pedir informação ou recusar: o solicitante lê")
            @Size(max = 1000, message = "Use no máximo 1000 caracteres") String note) {
    }

    public record ModerationNote(
            @NotBlank(message = "Informe o motivo") @Size(max = 1000, message = "Use no máximo 1000 caracteres") String note) {
    }
}
