package com.gearhub.ads;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Anúncios no site: o que vai ao ar em cada espaço, e o registro de impressões e cliques. */
@RestController
@RequestMapping("/api/v1/ads")
@Tag(name = "Publicidade", description = "Anúncios nos espaços do site, impressões e cliques")
public class AdController {

    private final AdServingService serving;

    public AdController(AdServingService serving) {
        this.serving = serving;
    }

    @GetMapping("/{slotKey}")
    @Operation(summary = "Anúncios de um espaço agora. Lista vazia: o site não mostra o espaço")
    public ResponseEntity<AdServingService.SlotAds> slot(@PathVariable String slotKey) {
        // Sem cache: a rotação e a vigência mudam a cada pedido
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(serving.serve(slotKey));
    }

    @PostMapping("/impressions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Registra que o anúncio ficou visível (conta uma vez por sessão a cada 30 minutos)")
    public void impression(@Valid @RequestBody AdRequests.Impression body) {
        serving.impression(body);
    }

    @GetMapping("/go/{creativeId}")
    @Operation(summary = "Clique no anúncio: registra e leva ao destino configurado")
    @ApiResponse(responseCode = "302", description = "Vai para o link de destino do criativo")
    public ResponseEntity<Void> go(@PathVariable UUID creativeId, @RequestParam String slot, @RequestParam String campaign,
            @RequestParam String sig, @RequestParam(required = false) String sid) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(serving.click(creativeId, slot, campaign, sig, sid))
                .cacheControl(CacheControl.noStore())
                .build();
    }
}
