package com.gearhub.market;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/market")
@Tag(name = "Games", description = "Preço, jogadores e avaliações dos jogos da Steam, com histórico da nossa coleta")
public class MarketController {

    private final MarketService market;

    public MarketController(MarketService market) {
        this.market = market;
    }

    @GetMapping("/games")
    @Operation(summary = "Lista os jogos acompanhados")
    public List<MarketService.GameSummary> games(
            @Parameter(description = "players, discount, price ou name") @RequestParam(required = false) String sort,
            @Parameter(description = "Busca pelo nome") @RequestParam(required = false) String q) {
        return market.list(sort, q);
    }

    @GetMapping("/games/{appid}")
    @Operation(summary = "Ficha completa de um jogo")
    @ApiResponse(responseCode = "404", description = "Jogo não acompanhado")
    public MarketService.GameDetail game(@PathVariable int appid) {
        return market.detail(appid);
    }

    @GetMapping("/games/{appid}/history")
    @Operation(summary = "Séries de preço e jogadores para os gráficos")
    public MarketService.History history(
            @PathVariable int appid,
            @Parameter(description = "24h, 7d, 30d ou all") @RequestParam(defaultValue = "30d") String range) {
        return market.history(appid, range);
    }
}
