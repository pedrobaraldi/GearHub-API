package com.gearhub.ads;

import com.gearhub.auth.AdminAccess;
import com.gearhub.auth.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
 * Publicidade no painel de admin (API pré-pronta; as telas entram no dashboard): espaços, anunciantes,
 * campanhas, criativos, aprovação e relatório. Só admin com a verificação em duas etapas ativa.
 */
@RestController
@RequestMapping("/api/v1/admin/ads")
@Tag(name = "Admin", description = "Painel de admin: denúncias, perfis e auditoria (só admin com 2FA)")
@SecurityRequirement(name = "session")
public class AdAdminController {

    private static final String FORBIDDEN = "Só admin acessa o painel de administração";

    private final AdminAccess access;
    private final AdAdminService ads;
    private final AdImageService images;

    public AdAdminController(AdminAccess access, AdAdminService ads, AdImageService images) {
        this.access = access;
        this.ads = ads;
        this.images = images;
    }

    @PostMapping(path = "/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Envia uma imagem de anúncio ou logotipo (JPG, PNG ou WebP, até 2 MB)")
    public Map<String, String> uploadImage(@AuthenticationPrincipal CurrentUser current, @RequestPart("file") MultipartFile file) {
        access.require(current, FORBIDDEN);
        return Map.of("url", images.store(current.id(), file));
    }

    // Espaços

    @GetMapping("/slots")
    @Operation(summary = "Espaços de anúncio")
    public List<AdAdminService.Slot> slots(@AuthenticationPrincipal CurrentUser current) {
        access.require(current, FORBIDDEN);
        return ads.slots();
    }

    @PostMapping("/slots")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Cria um espaço. O site só desenha espaços que já conhece pela chave")
    public AdAdminService.Slot createSlot(@AuthenticationPrincipal CurrentUser current, @Valid @RequestBody AdRequests.SaveSlot body) {
        access.require(current, FORBIDDEN);
        return ads.saveSlot(current.id(), null, body);
    }

    @PutMapping("/slots/{id}")
    @Operation(summary = "Edita um espaço")
    public AdAdminService.Slot updateSlot(@AuthenticationPrincipal CurrentUser current, @PathVariable int id,
            @Valid @RequestBody AdRequests.SaveSlot body) {
        access.require(current, FORBIDDEN);
        return ads.saveSlot(current.id(), id, body);
    }

    @DeleteMapping("/slots/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove um espaço (exclusão lógica)")
    public void deleteSlot(@AuthenticationPrincipal CurrentUser current, @PathVariable int id) {
        access.require(current, FORBIDDEN);
        ads.deleteSlot(current.id(), id);
    }

    // Anunciantes

    @GetMapping("/advertisers")
    @Operation(summary = "Anunciantes (documento mascarado)")
    public List<AdAdminService.Advertiser> advertisers(@AuthenticationPrincipal CurrentUser current) {
        access.require(current, FORBIDDEN);
        return ads.advertisers();
    }

    @GetMapping("/advertisers/{id}")
    @Operation(summary = "Ficha do anunciante, com o documento inteiro (o acesso fica na auditoria)")
    public AdAdminService.Advertiser advertiser(@AuthenticationPrincipal CurrentUser current, @PathVariable UUID id) {
        access.require(current, FORBIDDEN);
        return ads.advertiser(current.id(), id);
    }

    @PostMapping("/advertisers")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Cadastra um anunciante")
    public AdAdminService.Advertiser createAdvertiser(@AuthenticationPrincipal CurrentUser current,
            @Valid @RequestBody AdRequests.SaveAdvertiser body) {
        access.require(current, FORBIDDEN);
        return ads.saveAdvertiser(current.id(), null, body);
    }

    @PutMapping("/advertisers/{id}")
    @Operation(summary = "Edita um anunciante")
    public AdAdminService.Advertiser updateAdvertiser(@AuthenticationPrincipal CurrentUser current, @PathVariable UUID id,
            @Valid @RequestBody AdRequests.SaveAdvertiser body) {
        access.require(current, FORBIDDEN);
        return ads.saveAdvertiser(current.id(), id, body);
    }

    @DeleteMapping("/advertisers/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove um anunciante (exclusão lógica; as campanhas dele saem do ar)")
    public void deleteAdvertiser(@AuthenticationPrincipal CurrentUser current, @PathVariable UUID id) {
        access.require(current, FORBIDDEN);
        ads.deleteAdvertiser(current.id(), id);
    }

    // Campanhas e criativos

    @GetMapping("/campaigns")
    @Operation(summary = "Campanhas, com a situação de agora (agendada, ativa, encerrada...)")
    public List<AdAdminService.Campaign> campaigns(@AuthenticationPrincipal CurrentUser current,
            @RequestParam(required = false) String advertiserId) {
        access.require(current, FORBIDDEN);
        return ads.campaigns(advertiserId);
    }

    @GetMapping("/campaigns/{id}")
    @Operation(summary = "Campanha com criativos, espaços e se cada criativo serve em cada espaço")
    public AdAdminService.Campaign campaign(@AuthenticationPrincipal CurrentUser current, @PathVariable UUID id) {
        access.require(current, FORBIDDEN);
        return ads.campaign(id);
    }

    @PostMapping("/campaigns")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Cria uma campanha em rascunho")
    public AdAdminService.Campaign createCampaign(@AuthenticationPrincipal CurrentUser current,
            @Valid @RequestBody AdRequests.SaveCampaign body) {
        access.require(current, FORBIDDEN);
        return ads.saveCampaign(current.id(), null, body);
    }

    @PutMapping("/campaigns/{id}")
    @Operation(summary = "Edita a campanha. Se estava aprovada ou pausada, volta para aprovação")
    public AdAdminService.Campaign updateCampaign(@AuthenticationPrincipal CurrentUser current, @PathVariable UUID id,
            @Valid @RequestBody AdRequests.SaveCampaign body) {
        access.require(current, FORBIDDEN);
        return ads.saveCampaign(current.id(), id, body);
    }

    @DeleteMapping("/campaigns/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove a campanha (exclusão lógica; os eventos ficam)")
    public void deleteCampaign(@AuthenticationPrincipal CurrentUser current, @PathVariable UUID id) {
        access.require(current, FORBIDDEN);
        ads.deleteCampaign(current.id(), id);
    }

    @PostMapping("/campaigns/{id}/actions")
    @Operation(summary = "Envia para aprovação (submit), aprova, reprova (com motivo), pausa ou retoma")
    public AdAdminService.Campaign act(@AuthenticationPrincipal CurrentUser current, @PathVariable UUID id,
            @Valid @RequestBody AdRequests.CampaignAction body) {
        access.require(current, FORBIDDEN);
        return ads.act(current.id(), id, body);
    }

    @GetMapping("/campaigns/{id}/report")
    @Operation(summary = "Relatório: impressões, cliques e CTR no total, por espaço, por dia e por criativo")
    public AdAdminService.Report report(@AuthenticationPrincipal CurrentUser current, @PathVariable UUID id) {
        access.require(current, FORBIDDEN);
        return ads.report(id);
    }

    @PostMapping("/campaigns/{id}/creatives")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Adiciona um criativo à campanha")
    public AdAdminService.Campaign addCreative(@AuthenticationPrincipal CurrentUser current, @PathVariable UUID id,
            @Valid @RequestBody AdRequests.SaveCreative body) {
        access.require(current, FORBIDDEN);
        return ads.saveCreative(current.id(), id, null, body);
    }

    @PutMapping("/campaigns/{id}/creatives/{creativeId}")
    @Operation(summary = "Edita um criativo")
    public AdAdminService.Campaign updateCreative(@AuthenticationPrincipal CurrentUser current, @PathVariable UUID id,
            @PathVariable UUID creativeId, @Valid @RequestBody AdRequests.SaveCreative body) {
        access.require(current, FORBIDDEN);
        return ads.saveCreative(current.id(), id, creativeId, body);
    }

    @DeleteMapping("/campaigns/{id}/creatives/{creativeId}")
    @Operation(summary = "Remove um criativo (exclusão lógica)")
    public AdAdminService.Campaign deleteCreative(@AuthenticationPrincipal CurrentUser current, @PathVariable UUID id,
            @PathVariable UUID creativeId) {
        access.require(current, FORBIDDEN);
        return ads.deleteCreative(current.id(), id, creativeId);
    }
}
