package com.gearhub.admin;

import com.gearhub.auth.AdminAccess;
import com.gearhub.auth.CurrentUser;
import com.gearhub.reports.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * API do painel de admin (pré-pronta): denúncias, ficha de moderação e histórico de cada perfil.
 * Só admin com a verificação em duas etapas ativa. Nada aqui age sozinho: a moderação decide.
 */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Admin", description = "Painel de admin: denúncias, perfis e auditoria (só admin com 2FA)")
@SecurityRequirement(name = "session")
public class AdminController {

    private static final String FORBIDDEN = "Só admin acessa o painel de administração";

    public record ReviewReports(
            @Schema(allowableValues = {"post", "review", "comment", "user"}) @NotBlank String kind,
            @NotNull UUID targetId,
            @Schema(allowableValues = {"dismissed", "resolved"}, description = "dismissed: nada errado; resolved: já tratado")
            @NotBlank String status,
            @Size(max = 500, message = "Use no máximo 500 caracteres") String note) {
    }

    private final AdminAccess access;
    private final AdminService admin;
    private final ReportService reports;
    private final AuditService audit;

    public AdminController(AdminAccess access, AdminService admin, ReportService reports, AuditService audit) {
        this.access = access;
        this.admin = admin;
        this.reports = reports;
        this.audit = audit;
    }

    @GetMapping("/overview")
    @Operation(summary = "Números do painel: denúncias pendentes, cadastros e publicações dos últimos 7 dias")
    public AdminService.Overview overview(@AuthenticationPrincipal CurrentUser current) {
        access.require(current, FORBIDDEN);
        return admin.overview();
    }

    @GetMapping("/reports")
    @Operation(summary = "Conteúdos e perfis denunciados, os mais denunciados primeiro")
    public List<ReportService.ReportedItem> reports(@AuthenticationPrincipal CurrentUser current,
            @Parameter(description = "pending (padrão), dismissed ou resolved") @RequestParam(required = false) String status,
            @Parameter(description = "Só o que é de um usuário") @RequestParam(required = false) String username) {
        access.require(current, FORBIDDEN);
        return reports.list(status, username);
    }

    @PostMapping("/reports/review")
    @Operation(summary = "Fecha as denúncias pendentes de um conteúdo ou perfil")
    public Map<String, Integer> review(@AuthenticationPrincipal CurrentUser current, @Valid @RequestBody ReviewReports body) {
        access.require(current, FORBIDDEN);
        return Map.of("closed", reports.review(current.id(), body.kind(), body.targetId(), body.status(), body.note()));
    }

    @GetMapping("/users/{username}")
    @Operation(summary = "Ficha de moderação: dados da conta, números e denúncias contra o perfil")
    public AdminService.UserFile user(@AuthenticationPrincipal CurrentUser current, @PathVariable String username) {
        access.require(current, FORBIDDEN);
        return admin.userFile(username);
    }

    @GetMapping("/users/{username}/activity")
    @Operation(summary = "Histórico (auditoria) do que a pessoa fez no site, do mais novo para o mais antigo")
    public AuditService.Page activity(@AuthenticationPrincipal CurrentUser current, @PathVariable String username,
            @RequestParam(required = false) Instant before, @RequestParam(defaultValue = "50") int size) {
        access.require(current, FORBIDDEN);
        return audit.activity(admin.userFile(username).id(), before, size);
    }
}
