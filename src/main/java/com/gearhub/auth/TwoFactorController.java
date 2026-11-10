package com.gearhub.auth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me/2fa")
@Tag(name = "Verificação em duas etapas", description = "App autenticador e códigos de recuperação")
@SecurityRequirement(name = "session")
public class TwoFactorController {

    private final TwoFactorService twoFactor;

    public TwoFactorController(TwoFactorService twoFactor) {
        this.twoFactor = twoFactor;
    }

    @GetMapping
    @Operation(summary = "Diz se a verificação está ativa e quantos códigos de recuperação restam")
    public TwoFactorService.Status status(@AuthenticationPrincipal CurrentUser current) {
        return twoFactor.status(current.id());
    }

    @PostMapping("/setup")
    @Operation(summary = "Gera o segredo para o QR Code. Só vale depois de confirmado em /enable")
    @ApiResponse(responseCode = "409", description = "Já está ativa")
    public TwoFactorService.Setup setup(@AuthenticationPrincipal CurrentUser current) {
        return twoFactor.startSetup(current.id());
    }

    @PostMapping("/enable")
    @Operation(summary = "Confirma o primeiro código do app, ativa e devolve os códigos de recuperação",
            description = "As outras sessões da conta são encerradas. Os códigos aparecem só nesta resposta")
    @ApiResponse(responseCode = "400", description = "Código inválido")
    public Map<String, List<String>> enable(
            @AuthenticationPrincipal CurrentUser current, @Valid @RequestBody TwoFactorRequests.Code body) {
        return Map.of("recoveryCodes", twoFactor.enable(current.id(), current.sessionId(), body.code()));
    }

    @PostMapping("/recovery-codes")
    @Operation(summary = "Troca os códigos de recuperação por novos. Os antigos deixam de valer")
    @ApiResponse(responseCode = "400", description = "Código inválido")
    public Map<String, List<String>> regenerate(
            @AuthenticationPrincipal CurrentUser current, @Valid @RequestBody TwoFactorRequests.Code body) {
        return Map.of("recoveryCodes", twoFactor.regenerateRecoveryCodes(current.id(), body.code()));
    }

    @PostMapping("/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Desativa. Pede a senha, quando a conta tem uma, e um código")
    @ApiResponse(responseCode = "400", description = "Senha ou código inválido")
    public void disable(@AuthenticationPrincipal CurrentUser current, @Valid @RequestBody TwoFactorRequests.Disable body) {
        twoFactor.disable(current.id(), body.password(), body.code());
    }
}
