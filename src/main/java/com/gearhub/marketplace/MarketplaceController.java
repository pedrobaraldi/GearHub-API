package com.gearhub.marketplace;

import com.gearhub.auth.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Marketplace: a vitrine (aberta sem login), a solicitação de loja parceira e a gestão da loja aprovada.
 * A compra acontece no site da loja; aqui só passa o clique, que fica registrado.
 */
@RestController
@RequestMapping("/api/v1/marketplace")
@Tag(name = "Marketplace", description = "Vitrine de lojas parceiras, solicitação de loja e gestão de produtos")
public class MarketplaceController {

    private final PartnerApplicationService applications;
    private final StoreService stores;
    private final StoreImageService images;

    public MarketplaceController(PartnerApplicationService applications, StoreService stores, StoreImageService images) {
        this.applications = applications;
        this.stores = stores;
        this.images = images;
    }

    // Vitrine (sem login) -------------------------------------------------------------

    @GetMapping("/stores")
    @Operation(summary = "Lojas parceiras ativas")
    public List<StoreService.StoreCard> stores() {
        return stores.stores();
    }

    @GetMapping("/stores/{slug}")
    @Operation(summary = "Página pública da loja, com os produtos ativos")
    public StoreService.StorePage store(@PathVariable String slug) {
        return stores.store(slug);
    }

    @GetMapping("/products")
    @Operation(summary = "Vitrine de produtos, com busca e filtro por categoria")
    public StoreService.ShowcasePage products(@RequestParam(required = false) String q,
            @Parameter(description = "Slug de uma categoria do GearHub") @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "1") int page) {
        return stores.showcase(q, category, page);
    }

    @GetMapping("/products/{id}")
    @Operation(summary = "Página pública do produto")
    public StoreService.ProductPage product(@PathVariable UUID id) {
        return stores.productPage(id);
    }

    @GetMapping("/go/{id}")
    @Operation(summary = "\"Comprar na loja\": registra o clique e leva ao site da loja com o click_id (gh_click)")
    @ApiResponse(responseCode = "302", description = "Vai para o link de compra cadastrado pela loja")
    public ResponseEntity<Void> go(@AuthenticationPrincipal CurrentUser current, @PathVariable UUID id,
            @Parameter(description = "De onde veio o clique, como produto ou loja") @RequestParam(required = false) String origem) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(stores.click(id, origem, current == null ? null : current.id()))
                .cacheControl(CacheControl.noStore())
                .build();
    }

    // Solicitação de loja ----------------------------------------------------------------

    @GetMapping("/application")
    @Operation(summary = "A sua solicitação de loja mais recente", security = @SecurityRequirement(name = "session"))
    @ApiResponse(responseCode = "204", description = "Você ainda não pediu loja")
    public ResponseEntity<PartnerApplicationService.Application> application(@AuthenticationPrincipal CurrentUser current) {
        return applications.mine(current.id()).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PutMapping("/application")
    @Operation(summary = "Salva o rascunho da solicitação (ou a complementação pedida pelo admin)", security = @SecurityRequirement(name = "session"))
    public PartnerApplicationService.Application saveApplication(@AuthenticationPrincipal CurrentUser current,
            @Valid @RequestBody MarketplaceRequests.SaveApplication body) {
        return applications.save(current.id(), body);
    }

    @PostMapping(path = "/application/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Envia um documento da validação (PDF, JPG ou PNG, até 5 MB)", security = @SecurityRequirement(name = "session"))
    public PartnerApplicationService.Document addDocument(@AuthenticationPrincipal CurrentUser current,
            @Parameter(description = "cnpj_card, social_contract, responsible_id ou other") @RequestParam String kind,
            @RequestPart("file") MultipartFile file) {
        return applications.addDocument(current.id(), kind, file);
    }

    @DeleteMapping("/application/documents/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Tira um documento da solicitação", security = @SecurityRequirement(name = "session"))
    public void removeDocument(@AuthenticationPrincipal CurrentUser current, @PathVariable UUID id) {
        applications.removeDocument(current.id(), id);
    }

    @PostMapping("/application/submit")
    @Operation(summary = "Envia a solicitação para análise", security = @SecurityRequirement(name = "session"))
    @ApiResponse(responseCode = "400", description = "Falta algum dado obrigatório ou documento")
    public PartnerApplicationService.Application submit(@AuthenticationPrincipal CurrentUser current) {
        return applications.submit(current.id());
    }

    // Loja aprovada ------------------------------------------------------------------------

    @GetMapping("/my-store")
    @Operation(summary = "A sua loja", security = @SecurityRequirement(name = "session"))
    public StoreService.MyStore myStore(@AuthenticationPrincipal CurrentUser current) {
        return stores.myStore(current.id());
    }

    @PutMapping("/my-store")
    @Operation(summary = "Edita o perfil público da loja", security = @SecurityRequirement(name = "session"))
    public StoreService.MyStore updateStore(@AuthenticationPrincipal CurrentUser current,
            @Valid @RequestBody MarketplaceRequests.SaveStore body) {
        return stores.updateStore(current.id(), body);
    }

    @PostMapping(path = "/my-store/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Envia uma imagem da loja ou de produto (JPG, PNG ou WebP, até 2 MB)", security = @SecurityRequirement(name = "session"))
    public Map<String, String> uploadImage(@AuthenticationPrincipal CurrentUser current, @RequestPart("file") MultipartFile file) {
        // Só lojistas enviam imagens públicas
        stores.myStore(current.id());
        return Map.of("url", images.store(current.id(), file));
    }

    @GetMapping("/my-store/products")
    @Operation(summary = "Produtos da sua loja, inclusive pausados e arquivados", security = @SecurityRequirement(name = "session"))
    public List<StoreService.MyProduct> myProducts(@AuthenticationPrincipal CurrentUser current) {
        return stores.myProducts(current.id());
    }

    @PostMapping("/my-store/products")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Cadastra um produto (publica na hora)", security = @SecurityRequirement(name = "session"))
    public StoreService.MyProduct createProduct(@AuthenticationPrincipal CurrentUser current,
            @Valid @RequestBody MarketplaceRequests.SaveProduct body) {
        return stores.createProduct(current.id(), body);
    }

    @PutMapping("/my-store/products/{id}")
    @Operation(summary = "Edita um produto", security = @SecurityRequirement(name = "session"))
    public StoreService.MyProduct updateProduct(@AuthenticationPrincipal CurrentUser current, @PathVariable UUID id,
            @Valid @RequestBody MarketplaceRequests.SaveProduct body) {
        return stores.updateProduct(current.id(), id, body);
    }

    @PostMapping("/my-store/products/{id}/status")
    @Operation(summary = "Ativa, pausa ou arquiva um produto", security = @SecurityRequirement(name = "session"))
    public StoreService.MyProduct productStatus(@AuthenticationPrincipal CurrentUser current, @PathVariable UUID id,
            @Valid @RequestBody MarketplaceRequests.ProductStatus body) {
        return stores.setProductStatus(current.id(), id, body.status());
    }

    @DeleteMapping("/my-store/products/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove um produto da loja (o registro fica guardado)", security = @SecurityRequirement(name = "session"))
    public void deleteProduct(@AuthenticationPrincipal CurrentUser current, @PathVariable UUID id) {
        stores.deleteProduct(current.id(), id);
    }
}
