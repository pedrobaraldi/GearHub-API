package com.gearhub.account;

import com.gearhub.auth.AuthProperties;
import com.gearhub.auth.CurrentUser;
import com.gearhub.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
@Tag(name = "Configurações", description = "Conta, senha, contas ligadas, sessões, seus dados e exclusão")
@SecurityRequirement(name = "session")
public class AccountController {

    private final AccountService accounts;
    private final AccountExport export;
    private final AuthProperties properties;

    public AccountController(AccountService accounts, AccountExport export, AuthProperties properties) {
        this.accounts = accounts;
        this.export = export;
        this.properties = properties;
    }

    @GetMapping("/account")
    @Operation(summary = "Resumo da conta: e-mail, senha, contas ligadas e verificação em duas etapas")
    public AccountService.Overview overview(@AuthenticationPrincipal CurrentUser current) {
        return accounts.overview(current.id());
    }

    @PostMapping("/account/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Troca a senha (ou cria uma, para quem só entra com Google ou Discord)",
            description = "Pede a senha atual quando existe e o código do app com 2FA ativa. Encerra as outras sessões")
    @ApiResponse(responseCode = "400", description = "Senha atual, código ou senha nova inválidos")
    public void changePassword(@AuthenticationPrincipal CurrentUser current,
            @Valid @RequestBody AccountRequests.ChangePassword body) {
        accounts.changePassword(current.id(), current.sessionId(), body);
    }

    @PostMapping("/account/email")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Troca o e-mail. Pede a senha e, com 2FA ativa, o código do app")
    @ApiResponse(responseCode = "400", description = "E-mail em uso, senha ou código inválidos")
    public void changeEmail(@AuthenticationPrincipal CurrentUser current,
            @Valid @RequestBody AccountRequests.ChangeEmail body) {
        accounts.changeEmail(current.id(), body);
    }

    @DeleteMapping("/account/providers/{provider}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Desliga o login com Google ou Discord, desde que sobre outro jeito de entrar")
    @ApiResponse(responseCode = "409", description = "Seria o único jeito de entrar: crie uma senha antes")
    public void disconnect(@AuthenticationPrincipal CurrentUser current, @PathVariable String provider) {
        accounts.disconnect(current.id(), provider);
    }

    @GetMapping("/sessions")
    @Operation(summary = "Aparelhos com a conta aberta")
    public List<AccountService.SessionInfo> sessions(@AuthenticationPrincipal CurrentUser current) {
        return accounts.sessions(current.id(), current.sessionId());
    }

    @DeleteMapping("/sessions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Encerra a sessão de um aparelho")
    public void signOut(@AuthenticationPrincipal CurrentUser current, @PathVariable UUID id) {
        accounts.signOut(current.id(), id);
    }

    @PostMapping("/sessions/sign-out-others")
    @Operation(summary = "Encerra todas as sessões, menos a deste navegador")
    public Map<String, Integer> signOutOthers(@AuthenticationPrincipal CurrentUser current) {
        return Map.of("closed", accounts.signOutOthers(current.id(), current.sessionId()));
    }

    @GetMapping(value = "/export", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Baixa uma cópia dos seus dados (LGPD, art. 18)")
    public ResponseEntity<Map<String, Object>> export(@AuthenticationPrincipal CurrentUser current) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"gearhub-meus-dados.json\"")
                .body(export.of(current.id()));
    }

    @PostMapping("/account/delete")
    @Operation(summary = "Pede a exclusão da conta",
            description = "A conta some do site na hora e fica encerrada em definitivo depois de 30 dias. Entrar de novo antes cancela. "
                    + "Nada é apagado de verdade: os dados ficam guardados (exclusão lógica)")
    @ApiResponse(responseCode = "400", description = "Confirmação, senha ou código inválidos")
    public ResponseEntity<AccountService.Deletion> delete(@AuthenticationPrincipal CurrentUser current,
            @Valid @RequestBody AccountRequests.DeleteAccount body) {
        AccountService.Deletion deletion = accounts.requestDeletion(current.id(), body);
        ResponseCookie cleared = ResponseCookie.from(OpenApiConfig.SESSION_COOKIE, "")
                .httpOnly(true).secure(properties.secureCookie()).sameSite("Lax").path("/").maxAge(Duration.ZERO).build();
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cleared.toString()).body(deletion);
    }
}
