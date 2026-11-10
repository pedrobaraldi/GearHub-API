package com.gearhub.catalog;

import com.gearhub.market.MarketService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public final class CatalogResponses {

    private CatalogResponses() {
    }

    @Schema(description = "Jogo em listas: busca, DLCs, parecidos")
    public record GameCard(
            @Schema(example = "elden-ring") String slug,
            @Schema(example = "Elden Ring") String name,
            String coverUrl,
            @Schema(example = "2022") Integer year,
            @Schema(description = "Abreviações das plataformas", example = "[\"PC\", \"PS5\"]") List<String> platforms,
            @Schema(description = "Nota total de 0 a 100", example = "95") Integer rating) {
    }

    @Schema(description = "Notas de 0 a 100 e quantos votos cada uma tem")
    public record Ratings(
            @Schema(description = "Crítica especializada") Integer critics,
            Integer criticsCount,
            @Schema(description = "Usuários do IGDB") Integer users,
            Integer usersCount,
            @Schema(description = "Crítica e usuários juntos") Integer total) {
    }

    @Schema(description = "Tempo médio para terminar, em horas, segundo os jogadores do IGDB")
    public record TimeToBeat(
            @Schema(description = "Só a história") Integer main,
            @Schema(description = "História e alguns extras") Integer extras,
            @Schema(description = "100%") Integer completionist,
            @Schema(description = "Quantas pessoas informaram") int count) {
    }

    public record Platform(String name, String abbreviation) {
    }

    @Schema(description = "Trailer no YouTube")
    public record Video(String name, @Schema(example = "D1mDo1CEMuE") String youtubeId) {
    }

    @Schema(description = "Suporte a português do Brasil")
    public record Portuguese(boolean audio, boolean subtitles, boolean interfaceText) {
    }

    @Schema(description = "Como dá para jogar junto. Máximos nulos quando o IGDB não informa")
    public record Multiplayer(Integer onlineMax, Integer offlineMax, boolean onlineCoop, boolean offlineCoop,
            boolean campaignCoop, boolean lanCoop, boolean splitscreen) {
    }

    @Schema(description = "Site oficial, lojas e comunidades")
    public record Link(@Schema(example = "Steam") String label, String url) {
    }

    @Schema(description = "Ficha completa do jogo, montada com os dados do IGDB")
    public record GamePage(
            int igdbId,
            String slug,
            String name,
            @Schema(description = "Main Game, Remake, Expansion...") String type,
            @Schema(description = "Em inglês, como vem do IGDB") String summary,
            String storyline,
            LocalDate releaseDate,
            String coverUrl,
            @Schema(description = "Arte larga para o topo da página") String artworkUrl,
            Ratings ratings,
            TimeToBeat timeToBeat,
            List<String> genres,
            List<String> themes,
            List<String> modes,
            List<String> perspectives,
            List<Platform> platforms,
            List<String> developers,
            List<String> publishers,
            List<String> engines,
            String franchise,
            List<String> screenshots,
            List<Video> videos,
            Portuguese portuguese,
            Multiplayer multiplayer,
            @Schema(description = "Classificação indicativa brasileira (ClassInd)", example = "16") String ageRating,
            @Schema(description = "Seguidores antes do lançamento") Integer hypes,
            List<Link> links,
            List<GameCard> dlcs,
            List<GameCard> expansions,
            List<GameCard> similar,
            Integer steamAppid,
            OffsetDateTime syncedAt) {
    }

    @Schema(description = "Nota da comunidade GearHub: média das reviews de jogo, de 0 a 10")
    public record Community(@Schema(example = "9.2") java.math.BigDecimal score, int reviewCount) {
    }

    @Schema(description = "Página do jogo: ficha do IGDB, nota da comunidade e, se acompanhado, preço e jogadores da Steam")
    public record GameResponse(GamePage game, MarketService.GameSummary steam, Community community) {
    }

    public record SteamLink(String slug) {
    }
}
