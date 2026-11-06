package com.gearhub.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class AuthRequests {

    private AuthRequests() {
    }

    public record ForgotPassword(
            @NotBlank(message = "Informe seu e-mail")
            @Email(message = "E-mail inválido")
            @Size(max = 254, message = "E-mail muito longo")
            String email,

            @Schema(description = "Token do Cloudflare Turnstile")
            String turnstileToken) {

        @Override
        public String toString() {
            return "ForgotPassword[]";
        }
    }

    public record ResetPassword(
            @Schema(description = "Token recebido no link do e-mail")
            @NotBlank(message = "Este link é inválido ou expirou. Peça um novo.")
            String token,

            @Schema(format = "password")
            @NotBlank(message = "Crie uma senha")
            @Size(min = 8, max = 128, message = "A senha deve ter de 8 a 128 caracteres")
            @StrongPassword
            String password,

            @Schema(format = "password")
            @NotBlank(message = "Confirme sua senha")
            String passwordConfirmation) {

        @Schema(hidden = true)
        @AssertTrue(message = "As senhas não coincidem")
        public boolean isPasswordConfirmed() {
            return passwordConfirmation == null || passwordConfirmation.equals(password);
        }

        @Override
        public String toString() {
            return "ResetPassword[]";
        }
    }

    public record Register(
            @NotBlank(message = "Informe seu nome")
            @Size(max = 60, message = "Use no máximo 60 caracteres")
            String displayName,

            @NotBlank(message = "Escolha um nome de usuário")
            @Pattern(regexp = "^[a-z0-9]{3,30}$", message = "Use de 3 a 30 letras minúsculas ou números")
            String username,

            @NotBlank(message = "Informe seu e-mail")
            @Email(message = "E-mail inválido")
            @Size(max = 254, message = "E-mail muito longo")
            String email,

            @Schema(format = "password")
            @NotBlank(message = "Crie uma senha")
            @Size(min = 8, max = 128, message = "A senha deve ter de 8 a 128 caracteres")
            @StrongPassword
            String password,

            @Schema(format = "password")
            @NotBlank(message = "Confirme sua senha")
            String passwordConfirmation,

            @Schema(description = "Token do Cloudflare Turnstile")
            String turnstileToken) {

        @Schema(hidden = true)
        @AssertTrue(message = "As senhas não coincidem")
        public boolean isPasswordConfirmed() {
            return passwordConfirmation == null || passwordConfirmation.equals(password);
        }

        @Override
        public String toString() {
            return "Register[username=" + username + "]";
        }
    }

    public record Login(
            @NotBlank(message = "Informe seu e-mail")
            @Size(max = 254, message = "E-mail muito longo")
            String email,

            @Schema(format = "password")
            @NotBlank(message = "Informe sua senha")
            @Size(max = 128, message = "Senha muito longa")
            String password,

            @Schema(description = "Token do Cloudflare Turnstile")
            String turnstileToken) {

        @Override
        public String toString() {
            return "Login[]";
        }
    }
}
