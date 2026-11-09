package com.gearhub.market;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Endpoints públicos da Steam, sem chave: detalhes e preço na loja, jogadores online agora e avaliações.
 * Os valores de preço vêm em centavos.
 */
@Component
public class SteamClient {

    private static final Logger log = LoggerFactory.getLogger(SteamClient.class);
    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP = new ParameterizedTypeReference<>() {
    };

    public record Price(String currency, int initialCents, int finalCents, int discountPercent) {
    }

    public record Details(String name, String headerImage, String shortDescription, String developers,
            List<String> genres, String releaseDate, boolean free, Price price) {
    }

    public record Reviews(int positive, int negative, String summary) {
    }

    private final MarketProperties properties;
    private final RestClient http = RestClient.create();

    public SteamClient(MarketProperties properties) {
        this.properties = properties;
    }

    @SuppressWarnings("unchecked")
    public Optional<Details> details(int appid) {
        try {
            Map<String, Object> body = http.get()
                    .uri(properties.storeUri() + "/api/appdetails?appids={id}&cc={cc}&l={lang}", appid,
                            properties.country(), properties.language())
                    .retrieve().body(JSON_MAP);
            Map<String, Object> entry = body == null ? null : (Map<String, Object>) body.get(String.valueOf(appid));
            if (entry == null || !Boolean.TRUE.equals(entry.get("success"))) {
                return Optional.empty();
            }
            Map<String, Object> data = (Map<String, Object>) entry.get("data");
            Map<String, Object> priceMap = (Map<String, Object>) data.get("price_overview");
            Price price = priceMap == null ? null : new Price(
                    (String) priceMap.get("currency"), number(priceMap.get("initial")), number(priceMap.get("final")),
                    number(priceMap.get("discount_percent")));
            List<String> developers = (List<String>) data.getOrDefault("developers", List.of());
            List<Map<String, Object>> genres = (List<Map<String, Object>>) data.getOrDefault("genres", List.of());
            Map<String, Object> release = (Map<String, Object>) data.get("release_date");
            return Optional.of(new Details(
                    (String) data.get("name"),
                    (String) data.get("header_image"),
                    (String) data.get("short_description"),
                    String.join(", ", developers),
                    genres.stream().map(genre -> (String) genre.get("description")).toList(),
                    release == null ? null : (String) release.get("date"),
                    Boolean.TRUE.equals(data.get("is_free")),
                    price));
        } catch (RestClientException | ClassCastException e) {
            log.warn("Steam: não consegui ler os detalhes do app {}: {}", appid, e.getMessage());
            return Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    public Optional<Integer> currentPlayers(int appid) {
        try {
            Map<String, Object> body = http.get()
                    .uri(properties.apiUri() + "/ISteamUserStats/GetNumberOfCurrentPlayers/v1/?appid={id}", appid)
                    .retrieve().body(JSON_MAP);
            Map<String, Object> response = body == null ? null : (Map<String, Object>) body.get("response");
            if (response == null || number(response.get("result")) != 1) {
                return Optional.empty();
            }
            return Optional.of(number(response.get("player_count")));
        } catch (RestClientException | ClassCastException e) {
            log.warn("Steam: não consegui ler os jogadores do app {}: {}", appid, e.getMessage());
            return Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    public Optional<Reviews> reviews(int appid) {
        try {
            Map<String, Object> body = http.get()
                    .uri(properties.storeUri() + "/appreviews/{id}?json=1&language=all&purchase_type=all&num_per_page=0", appid)
                    .retrieve().body(JSON_MAP);
            Map<String, Object> summary = body == null ? null : (Map<String, Object>) body.get("query_summary");
            if (summary == null) {
                return Optional.empty();
            }
            return Optional.of(new Reviews(number(summary.get("total_positive")), number(summary.get("total_negative")),
                    (String) summary.get("review_score_desc")));
        } catch (RestClientException | ClassCastException e) {
            log.warn("Steam: não consegui ler as avaliações do app {}: {}", appid, e.getMessage());
            return Optional.empty();
        }
    }

    private static int number(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }
}
