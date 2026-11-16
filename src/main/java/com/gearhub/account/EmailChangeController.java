package com.gearhub.account;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Link do e-mail de troca de endereço. Aberto sem login: quem tem o link provou que recebe e-mails ali. */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Autenticação", description = "Cadastro, login e sessão por cookie")
public class EmailChangeController {

    private final AccountService accounts;

    public EmailChangeController(AccountService accounts) {
        this.accounts = accounts;
    }

    @PostMapping("/confirm-email")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Confirma o e-mail do cadastro, ou a troca para o endereço novo, com o token do link enviado por e-mail")
    @ApiResponse(responseCode = "400", description = "Link inválido, já usado ou expirado")
    @ApiResponse(responseCode = "409", description = "O e-mail passou a ter outra conta nesse meio-tempo")
    public void confirm(@Valid @RequestBody AccountRequests.ConfirmEmail body) {
        accounts.confirmEmail(body.token());
    }
}
