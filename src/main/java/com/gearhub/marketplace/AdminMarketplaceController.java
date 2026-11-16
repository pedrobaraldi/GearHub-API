package com.gearhub.marketplace;

import com.gearhub.auth.AdminAccess;
import com.gearhub.auth.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Marketplace no painel de admin (API pré-pronta; as telas entram no dashboard): análise das solicitações de
 * loja, documentos e moderação de lojas e produtos. Só admin com a verificação em duas etapas ativa.
 */
@RestController
@RequestMapping("/api/v1/admin/marketplace")
@Tag(name = "Admin", description = "Painel de admin: denúncias, perfis e auditoria (só admin com 2FA)")
@SecurityRequirement(name = "session")
public class AdminMarketplaceController {

    private static final String FORBIDDEN = "Só admin acessa o painel de administração";

    private final AdminAccess access;
    private final PartnerApplicationService applications;
    private final StoreService stores;

    public AdminMarketplaceController(AdminAccess access, PartnerApplicationService applications, StoreService stores) {
        this.access = access;
        this.applications = applications;
        this.stores = stores;
    }

    @GetMapping("/applications")
    @Operation(summary = "Fila de solicitações de loja (sem os rascunhos), as mais antigas primeiro")
    public List<PartnerApplicationService.QueueItem> queue(@AuthenticationPrincipal CurrentUser current,
            @Parameter(description = "pending, in_review, awaiting_info, approved ou rejected; vazio traz todas")
            @RequestParam(required = false) String status) {
        access.require(current, FORBIDDEN);
        return applications.queue(status);
    }

    @GetMapping("/applications/{id}")
    @Operation(summary = "Solicitação completa, com CPF, documentos e histórico (o acesso fica na auditoria)")
    public PartnerApplicationService.AdminView application(@AuthenticationPrincipal CurrentUser current, @PathVariable UUID id) {
        access.require(current, FORBIDDEN);
        return applications.adminView(current.id(), id);
    }

    @GetMapping("/applications/{id}/documents/{documentId}")
    @Operation(summary = "Baixa um documento da solicitação (o acesso fica na auditoria)")
    public ResponseEntity<byte[]> document(@AuthenticationPrincipal CurrentUser current, @PathVariable UUID id,
            @PathVariable UUID documentId) {
        access.require(current, FORBIDDEN);
        PartnerApplicationService.DocumentFile file = applications.document(current.id(), id, documentId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.fileName()).build().toString())
                .cacheControl(CacheControl.noStore())
                .body(file.content());
    }

    @PostMapping("/applications/{id}/review")
    @Operation(summary = "Coloca em análise, pede informação, aprova (cria a loja) ou recusa. Notifica o solicitante")
    public PartnerApplicationService.AdminView review(@AuthenticationPrincipal CurrentUser current, @PathVariable UUID id,
            @Valid @RequestBody MarketplaceRequests.ReviewApplication body) {
        access.require(current, FORBIDDEN);
        return applications.review(current.id(), id, body);
    }

    @PostMapping("/products/{id}/remove")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Tira um produto do ar (sem apagar), com o motivo que o lojista vê")
    public void removeProduct(@AuthenticationPrincipal CurrentUser current, @PathVariable UUID id,
            @Valid @RequestBody MarketplaceRequests.ModerationNote body) {
        access.require(current, FORBIDDEN);
        stores.removeProduct(current.id(), id, body.note());
    }

    @PostMapping("/products/{id}/restore")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Volta um produto ao ar")
    public void restoreProduct(@AuthenticationPrincipal CurrentUser current, @PathVariable UUID id) {
        access.require(current, FORBIDDEN);
        stores.restoreProduct(current.id(), id);
    }

    @PostMapping("/stores/{slug}/suspend")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Suspende a loja: ela e os produtos somem da vitrine")
    public void suspendStore(@AuthenticationPrincipal CurrentUser current, @PathVariable String slug,
            @Valid @RequestBody MarketplaceRequests.ModerationNote body) {
        access.require(current, FORBIDDEN);
        stores.setStoreStatus(current.id(), slug, false, body.note());
    }

    @PostMapping("/stores/{slug}/reactivate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Reativa uma loja suspensa")
    public void reactivateStore(@AuthenticationPrincipal CurrentUser current, @PathVariable String slug) {
        access.require(current, FORBIDDEN);
        stores.setStoreStatus(current.id(), slug, true, null);
    }
}
