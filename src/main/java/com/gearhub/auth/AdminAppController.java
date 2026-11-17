package com.gearhub.auth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Login do painel de admin (programa de desktop) e os aparelhos com o painel aberto. */
@RestController
@Tag(name = "Admin", description = "Painel de admin: denúncias, perfis e auditoria (só admin com 2FA)")
public class AdminAppController {

    private static final String FORBIDDEN = "Só admin acessa o painel de administração";

    @Schema(description = "Login do painel: senha e código do 2FA juntos")
    public record AdminLogin(
            @NotBlank @Email @Size(max = 254) String email,
            @Schema(format = "password") @NotBlank @Size(max = 128) String password,
            @Schema(description = "Código do app autenticador ou de recuperação") @NotBlank @Size(max = 32) String code,
            @Schema(description = "Nome do aparelho, mostrado na lista de acessos", example = "Notebook da Ana") @Size(max = 80) String deviceName) {
        @Override
        public String toString() {
            return "AdminLogin[]";
        }
    }

    private final AdminAppTokenService tokens;
    private final AdminAccess access;

    public AdminAppController(AdminAppTokenService tokens, AdminAccess access) {
        this.tokens = tokens;
        this.access = access;
    }

    @PostMapping("/api/v1/auth/admin/login")
    @Operation(summary = "Entra no painel de admin: devolve um token de 8 horas (Authorization: Bearer), só para admin com 2FA")
    @ApiResponse(responseCode = "401", description = "E-mail, senha ou código incorretos (a mesma resposta para qualquer falha)")
    public ResponseEntity<AdminAppTokenService.Login> login(@Valid @RequestBody AdminLogin body, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(tokens.login(body.email(), body.password(), body.code(), body.deviceName(), request.getRemoteAddr()));
    }

    @PostMapping("/api/v1/auth/admin/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Sai do painel: encerra o token desta requisição", security = @SecurityRequirement(name = "session"))
    public void logout(@AuthenticationPrincipal CurrentUser current, HttpServletRequest request) {
        if (request.getAttribute(AdminTokenAuthenticationFilter.DEVICE_ATTRIBUTE) == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use o token do painel para sair dele.");
        }
        tokens.revoke(current.id(), current.sessionId());
    }

    @GetMapping("/api/v1/admin/app-tokens")
    @Operation(summary = "Aparelhos com o painel aberto (de toda a equipe)", security = @SecurityRequirement(name = "session"))
    public List<AdminAppTokenService.AppToken> appTokens(@AuthenticationPrincipal CurrentUser current) {
        access.require(current, FORBIDDEN);
        return tokens.list(current.sessionId());
    }

    @PostMapping("/api/v1/admin/app-tokens/{id}/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Encerra o acesso de um aparelho (por exemplo, notebook perdido)", security = @SecurityRequirement(name = "session"))
    public void revoke(@AuthenticationPrincipal CurrentUser current, @PathVariable UUID id) {
        access.require(current, FORBIDDEN);
        tokens.revoke(current.id(), id);
    }
}
