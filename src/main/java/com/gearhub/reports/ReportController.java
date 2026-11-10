package com.gearhub.reports;

import com.gearhub.auth.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Denúncias", description = "Denunciar posts, reviews, comentários e perfis")
@SecurityRequirement(name = "session")
public class ReportController {

    private final ReportService reports;

    public ReportController(ReportService reports) {
        this.reports = reports;
    }

    @GetMapping("/report-reasons")
    @Operation(summary = "Motivos de denúncia, na ordem em que aparecem no site")
    public List<ReportService.Reason> reasons() {
        return reports.reasons();
    }

    @PostMapping("/reports")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Denuncia um post, review, comentário ou perfil. Fica pendente para a moderação")
    @ApiResponse(responseCode = "400", description = "Motivo inválido ou denúncia do próprio conteúdo")
    @ApiResponse(responseCode = "404", description = "Conteúdo ou perfil não existe")
    @ApiResponse(responseCode = "409", description = "Já denunciado por essa pessoa")
    public void report(@AuthenticationPrincipal CurrentUser current, @Valid @RequestBody ReportService.CreateReport body) {
        reports.report(current.id(), body);
    }
}
