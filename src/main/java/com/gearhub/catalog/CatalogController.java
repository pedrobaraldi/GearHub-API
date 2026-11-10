package com.gearhub.catalog;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/catalog")
@Tag(name = "Catálogo de jogos", description = "Ficha completa e busca de jogos, com dados do IGDB.com")
@SecurityRequirement(name = "session")
public class CatalogController {

    private final CatalogService catalog;

    public CatalogController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/games")
    @Operation(summary = "Busca jogos pelo nome no catálogo inteiro (sem DLCs, pacotes e mods)")
    @ApiResponse(responseCode = "400", description = "Termo com menos de 2 caracteres")
    @ApiResponse(responseCode = "503", description = "IGDB indisponível ou sem credenciais")
    public List<CatalogResponses.GameCard> search(@Parameter(description = "Nome do jogo") @RequestParam String q) {
        return catalog.search(q);
    }

    @GetMapping("/games/{slug}")
    @Operation(summary = "Página do jogo: ficha do IGDB e, se acompanhado, preço e jogadores da Steam")
    @ApiResponse(responseCode = "404", description = "Jogo não existe no catálogo")
    @ApiResponse(responseCode = "503", description = "IGDB indisponível e sem cópia guardada")
    public CatalogResponses.GameResponse game(@PathVariable String slug) {
        return catalog.game(slug);
    }

    @GetMapping("/steam/{appid}")
    @Operation(summary = "Encontra a página do jogo a partir do appid da Steam (endereços antigos da aba Games)")
    @ApiResponse(responseCode = "404", description = "Jogo sem página no catálogo")
    public CatalogResponses.SteamLink steam(@PathVariable int appid) {
        String slug = catalog.slugForSteam(appid);
        if (slug == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Jogo não encontrado");
        }
        return new CatalogResponses.SteamLink(slug);
    }
}
