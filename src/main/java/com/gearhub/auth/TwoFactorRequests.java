package com.gearhub.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class TwoFactorRequests {

    private TwoFactorRequests() {
    }

    public record Code(
            @Schema(description = "6 dígitos do app autenticador ou um código de recuperação", example = "123456")
            @NotBlank(message = "Digite o código")
            @Size(max = 32, message = "Código inválido. Confira o app e tente de novo.")
            String code) {

        @Override
        public String toString() {
            return "Code[]";
        }
    }

    public record Disable(
            @Schema(format = "password", description = "Obrigatória quando a conta tem senha")
            @Size(max = 128, message = "Senha muito longa")
            String password,

            @Schema(description = "6 dígitos do app autenticador ou um código de recuperação")
            @NotBlank(message = "Digite o código")
            @Size(max = 32, message = "Código inválido. Confira o app e tente de novo.")
            String code) {

        @Override
        public String toString() {
            return "Disable[]";
        }
    }
}
