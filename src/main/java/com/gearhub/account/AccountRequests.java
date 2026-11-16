package com.gearhub.account;

import com.gearhub.auth.StrongPassword;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Pedidos das Configurações. Ações sensíveis confirmam com a senha (quando a conta tem uma)
 * e com o código do app (quando a verificação em duas etapas está ativa).
 */
public final class AccountRequests {

    private AccountRequests() {
    }

    public record ChangePassword(
            @Schema(format = "password", description = "Senha atual. Obrigatória quando a conta já tem senha")
            @Size(max = 128, message = "Senha muito longa")
            String currentPassword,

            @Schema(format = "password")
            @NotBlank(message = "Crie uma senha")
            @Size(min = 8, max = 128, message = "A senha deve ter de 8 a 128 caracteres")
            @StrongPassword
            String password,

            @Schema(format = "password")
            @NotBlank(message = "Confirme sua senha")
            String passwordConfirmation,

            @Schema(description = "Código do app autenticador ou de recuperação, com 2FA ativa")
            @Size(max = 32, message = "Código inválido. Confira o app e tente de novo.")
            String code) {

        @Schema(hidden = true)
        @AssertTrue(message = "As senhas não coincidem")
        public boolean isPasswordConfirmed() {
            return passwordConfirmation == null || passwordConfirmation.equals(password);
        }

        @Override
        public String toString() {
            return "ChangePassword[]";
        }
    }

    public record ChangeEmail(
            @NotBlank(message = "Informe o novo e-mail")
            @Email(message = "E-mail inválido")
            @Size(max = 254, message = "E-mail muito longo")
            String email,

            @Schema(format = "password", description = "Senha atual")
            @Size(max = 128, message = "Senha muito longa")
            String password,

            @Size(max = 32, message = "Código inválido. Confira o app e tente de novo.")
            String code) {

        @Override
        public String toString() {
            return "ChangeEmail[]";
        }
    }

    @Schema(description = "Confirmação de uma ação sensível: a senha (quando a conta tem uma) e o código do app (com 2FA)")
    public record Confirm(
            @Schema(format = "password")
            @Size(max = 128, message = "Senha muito longa")
            String password,
            @Size(max = 32, message = "Código inválido. Confira o app e tente de novo.")
            String code) {
        @Override
        public String toString() {
            return "Confirm[]";
        }
    }

    public record ConfirmEmail(
            @NotBlank(message = "Link inválido")
            @Size(max = 200, message = "Link inválido")
            String token) {
        @Override
        public String toString() {
            return "ConfirmEmail[]";
        }
    }

    public record DeleteAccount(
            @Schema(description = "O nome de usuário, digitado para confirmar", example = "pedrolm")
            @NotBlank(message = "Digite seu nome de usuário para confirmar")
            @Size(max = 40, message = "Nome de usuário incorreto")
            String confirmation,

            @Schema(format = "password", description = "Senha atual, quando a conta tem uma")
            @Size(max = 128, message = "Senha muito longa")
            String password,

            @Size(max = 32, message = "Código inválido. Confira o app e tente de novo.")
            String code) {

        @Override
        public String toString() {
            return "DeleteAccount[]";
        }
    }
}
