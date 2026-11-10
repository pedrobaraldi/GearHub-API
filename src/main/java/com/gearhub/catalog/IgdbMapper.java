package com.gearhub.catalog;

import com.gearhub.catalog.CatalogResponses.GameCard;
import com.gearhub.catalog.CatalogResponses.GamePage;
import com.gearhub.catalog.CatalogResponses.Link;
import com.gearhub.catalog.CatalogResponses.Multiplayer;
import com.gearhub.catalog.CatalogResponses.Platform;
import com.gearhub.catalog.CatalogResponses.Portuguese;
import com.gearhub.catalog.CatalogResponses.Ratings;
import com.gearhub.catalog.CatalogResponses.TimeToBeat;
import com.gearhub.catalog.CatalogResponses.Video;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Converte as respostas do IGDB nos formatos da API. Tudo que vem de fora é conferido: links só http(s),
 * ids de imagem e vídeo só com caracteres esperados, para nada estranho chegar a um src ou href no site.
 */
final class IgdbMapper {

    /** Campos pedidos para a página do jogo. */
    static final String GAME_FIELDS = """
            fields name,slug,summary,storyline,first_release_date,game_type.type,
            aggregated_rating,aggregated_rating_count,rating,rating_count,total_rating,hypes,
            cover.image_id,artworks.image_id,screenshots.image_id,videos.name,videos.video_id,
            genres.name,themes.name,game_modes.name,player_perspectives.name,platforms.name,platforms.abbreviation,
            involved_companies.company.name,involved_companies.developer,involved_companies.publisher,
            game_engines.name,franchises.name,websites.url,websites.type.type,
            external_games.external_game_source,external_games.uid,external_games.url,
            language_supports.language.locale,language_supports.language_support_type.name,
            multiplayer_modes.onlinemax,multiplayer_modes.offlinemax,multiplayer_modes.onlinecoop,
            multiplayer_modes.offlinecoop,multiplayer_modes.campaigncoop,multiplayer_modes.lancoop,
            multiplayer_modes.splitscreen,age_ratings.organization.name,age_ratings.rating_category.rating,
            dlcs.name,dlcs.slug,dlcs.cover.image_id,dlcs.first_release_date,
            expansions.name,expansions.slug,expansions.cover.image_id,expansions.first_release_date,
            similar_games.name,similar_games.slug,similar_games.cover.image_id,similar_games.first_release_date;
            """;

    /** Campos dos cards da busca. */
    static final String CARD_FIELDS = "fields name,slug,cover.image_id,first_release_date,platforms.abbreviation,total_rating;";

    /** Jogo principal, expansão independente, remake, remaster, versão expandida e port: sem DLC, pacote ou mod. */
    static final String PLAYABLE_TYPES = "game_type = (0,4,8,9,10,11) & version_parent = null";

    static final int STEAM_SOURCE = 1;

    private static final String IMAGE_BASE = "https://images.igdb.com/igdb/image/upload/t_";
    private static final Pattern IMAGE_ID = Pattern.compile("[a-z0-9]{1,40}");
    private static final Pattern YOUTUBE_ID = Pattern.compile("[A-Za-z0-9_-]{6,20}");
    private static final Pattern SLUG = Pattern.compile("[a-z0-9][a-z0-9-]{0,119}");
    private static final int MAX_SCREENSHOTS = 10;
    private static final int MAX_VIDEOS = 3;
    private static final int MAX_CARDS = 12;

    // Sites que valem um botão, na ordem em que aparecem. O resto fica de fora
    private static final Map<String, String> WEBSITE_LABELS = new LinkedHashMap<>();

    static {
        WEBSITE_LABELS.put("Official Website", "Site oficial");
        WEBSITE_LABELS.put("Steam", "Steam");
        WEBSITE_LABELS.put("Epic", "Epic Games");
        WEBSITE_LABELS.put("GOG", "GOG");
        WEBSITE_LABELS.put("Playstation", "PlayStation");
        WEBSITE_LABELS.put("Xbox", "Xbox");
        WEBSITE_LABELS.put("Nintendo", "Nintendo");
        WEBSITE_LABELS.put("Community Wiki", "Wiki");
        WEBSITE_LABELS.put("Wikipedia", "Wikipedia");
        WEBSITE_LABELS.put("Discord", "Discord");
        WEBSITE_LABELS.put("Subreddit", "Reddit");
        WEBSITE_LABELS.put("YouTube", "YouTube");
        WEBSITE_LABELS.put("Twitch", "Twitch");
        WEBSITE_LABELS.put("Twitter", "X");
    }

    // Lojas que o IGDB informa em external_games, quando o jogo não tem o site correspondente
    private static final Map<Integer, String> STORE_SOURCES = Map.of(
            1, "Steam", 5, "GOG", 11, "Xbox", 26, "Epic Games", 36, "PlayStation");

    private IgdbMapper() {
    }

    static boolean validSlug(String slug) {
        return slug != null && SLUG.matcher(slug).matches();
    }

    /** Texto livre dentro de {@code search "..."}: sem aspas, barras ou ponto e vírgula, que mudariam a consulta. */
    static String searchTerm(String term) {
        return term == null ? "" : term.replaceAll("[\"\\\\;]", " ").replaceAll("\\s+", " ").strip();
    }

    static GameCard card(Map<String, Object> game) {
        return new GameCard(
                text(game, "slug"),
                text(game, "name"),
                image(object(game, "cover"), "cover_big"),
                year(game.get("first_release_date")),
                list(game, "platforms").stream().map(platform -> text(platform, "abbreviation"))
                        .filter(Objects::nonNull).toList(),
                rounded(game.get("total_rating")));
    }

    static GamePage page(Map<String, Object> game, Map<String, Object> timeToBeat) {
        List<Map<String, Object>> companies = list(game, "involved_companies");
        List<Map<String, Object>> external = list(game, "external_games");
        return new GamePage(
                number(game.get("id")),
                text(game, "slug"),
                text(game, "name"),
                text(object(game, "game_type"), "type"),
                text(game, "summary"),
                text(game, "storyline"),
                date(game.get("first_release_date")),
                image(object(game, "cover"), "cover_big_2x"),
                list(game, "artworks").stream().map(art -> image(art, "1080p")).filter(Objects::nonNull)
                        .findFirst().orElse(null),
                new Ratings(rounded(game.get("aggregated_rating")), integer(game.get("aggregated_rating_count")),
                        rounded(game.get("rating")), integer(game.get("rating_count")), rounded(game.get("total_rating"))),
                timeToBeat(timeToBeat),
                names(game, "genres"),
                names(game, "themes"),
                names(game, "game_modes"),
                names(game, "player_perspectives"),
                list(game, "platforms").stream()
                        .map(platform -> new Platform(text(platform, "name"), text(platform, "abbreviation")))
                        .filter(platform -> platform.name() != null).toList(),
                companies.stream().filter(company -> Boolean.TRUE.equals(company.get("developer")))
                        .map(company -> text(object(company, "company"), "name")).filter(Objects::nonNull).toList(),
                companies.stream().filter(company -> Boolean.TRUE.equals(company.get("publisher")))
                        .map(company -> text(object(company, "company"), "name")).filter(Objects::nonNull).toList(),
                names(game, "game_engines"),
                names(game, "franchises").stream().findFirst().orElse(null),
                list(game, "screenshots").stream().map(shot -> image(shot, "screenshot_huge")).filter(Objects::nonNull)
                        .limit(MAX_SCREENSHOTS).toList(),
                list(game, "videos").stream()
                        .filter(video -> text(video, "video_id") != null && YOUTUBE_ID.matcher(text(video, "video_id")).matches())
                        .map(video -> new Video(text(video, "name"), text(video, "video_id")))
                        .limit(MAX_VIDEOS).toList(),
                portuguese(list(game, "language_supports")),
                multiplayer(list(game, "multiplayer_modes")),
                list(game, "age_ratings").stream()
                        .filter(rating -> "CLASS_IND".equals(text(object(rating, "organization"), "name")))
                        .map(rating -> text(object(rating, "rating_category"), "rating"))
                        .filter(Objects::nonNull).findFirst().orElse(null),
                integer(game.get("hypes")),
                links(list(game, "websites"), external),
                cards(game, "dlcs"),
                cards(game, "expansions"),
                cards(game, "similar_games"),
                steamAppid(external),
                OffsetDateTime.now(ZoneOffset.UTC));
    }

    static Integer steamAppid(List<Map<String, Object>> external) {
        return external.stream()
                .filter(entry -> integer(entry.get("external_game_source")) != null
                        && integer(entry.get("external_game_source")) == STEAM_SOURCE)
                .map(entry -> text(entry, "uid"))
                .filter(uid -> uid != null && uid.matches("\\d{1,9}"))
                .map(Integer::valueOf)
                .findFirst().orElse(null);
    }

    private static TimeToBeat timeToBeat(Map<String, Object> row) {
        if (row == null) {
            return null;
        }
        Integer main = hours(row.get("hastily"));
        Integer extras = hours(row.get("normally"));
        Integer completionist = hours(row.get("completely"));
        if (main == null && extras == null && completionist == null) {
            return null;
        }
        Integer count = integer(row.get("count"));
        return new TimeToBeat(main, extras, completionist, count == null ? 0 : count);
    }

    private static Portuguese portuguese(List<Map<String, Object>> supports) {
        boolean audio = false;
        boolean subtitles = false;
        boolean interfaceText = false;
        for (Map<String, Object> support : supports) {
            if (!"pt-BR".equals(text(object(support, "language"), "locale"))) {
                continue;
            }
            String type = text(object(support, "language_support_type"), "name");
            audio |= "Audio".equals(type);
            subtitles |= "Subtitles".equals(type);
            interfaceText |= "Interface".equals(type);
        }
        return new Portuguese(audio, subtitles, interfaceText);
    }

    private static Multiplayer multiplayer(List<Map<String, Object>> modes) {
        if (modes.isEmpty()) {
            return null;
        }
        Integer onlineMax = null;
        Integer offlineMax = null;
        boolean onlineCoop = false;
        boolean offlineCoop = false;
        boolean campaignCoop = false;
        boolean lanCoop = false;
        boolean splitscreen = false;
        // Um registro por plataforma: junta tudo, ficando com o maior número de jogadores
        for (Map<String, Object> mode : modes) {
            onlineMax = max(onlineMax, integer(mode.get("onlinemax")));
            offlineMax = max(offlineMax, integer(mode.get("offlinemax")));
            onlineCoop |= Boolean.TRUE.equals(mode.get("onlinecoop"));
            offlineCoop |= Boolean.TRUE.equals(mode.get("offlinecoop"));
            campaignCoop |= Boolean.TRUE.equals(mode.get("campaigncoop"));
            lanCoop |= Boolean.TRUE.equals(mode.get("lancoop"));
            splitscreen |= Boolean.TRUE.equals(mode.get("splitscreen"));
        }
        return new Multiplayer(onlineMax, offlineMax, onlineCoop, offlineCoop, campaignCoop, lanCoop, splitscreen);
    }

    private static List<Link> links(List<Map<String, Object>> websites, List<Map<String, Object>> external) {
        Map<String, String> byLabel = new LinkedHashMap<>();
        WEBSITE_LABELS.forEach((type, label) -> websites.stream()
                .filter(site -> type.equals(text(object(site, "type"), "type")))
                .map(site -> safeUrl(text(site, "url")))
                .filter(Objects::nonNull)
                .findFirst()
                .ifPresent(url -> byLabel.put(label, url)));
        for (Map<String, Object> entry : external) {
            String label = STORE_SOURCES.get(integer(entry.get("external_game_source")));
            String url = safeUrl(text(entry, "url"));
            if (label != null && url != null) {
                byLabel.putIfAbsent(label, url);
            }
        }
        Integer steam = steamAppid(external);
        if (steam != null) {
            byLabel.putIfAbsent("Steam", "https://store.steampowered.com/app/" + steam);
        }
        // Mantém a ordem de WEBSITE_LABELS, com as lojas encontradas só em external_games nos seus lugares
        List<Link> ordered = new ArrayList<>();
        WEBSITE_LABELS.values().forEach(label -> {
            if (byLabel.containsKey(label)) {
                ordered.add(new Link(label, byLabel.get(label)));
            }
        });
        return ordered;
    }

    private static List<GameCard> cards(Map<String, Object> game, String key) {
        return list(game, key).stream()
                .filter(item -> validSlug(text(item, "slug")) && text(item, "name") != null)
                .map(IgdbMapper::card)
                .limit(MAX_CARDS)
                .toList();
    }

    private static String image(Map<String, Object> image, String size) {
        String id = text(image, "image_id");
        return id != null && IMAGE_ID.matcher(id).matches() ? IMAGE_BASE + size + "/" + id + ".jpg" : null;
    }

    private static String safeUrl(String url) {
        return url != null && (url.startsWith("https://") || url.startsWith("http://")) && url.length() <= 500 ? url : null;
    }

    private static List<String> names(Map<String, Object> game, String key) {
        return list(game, key).stream().map(item -> text(item, "name")).filter(Objects::nonNull).toList();
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> list(Map<String, Object> source, String key) {
        Object value = source == null ? null : source.get(key);
        if (!(value instanceof List<?> items)) {
            return List.of();
        }
        return items.stream().filter(Map.class::isInstance).map(item -> (Map<String, Object>) item).toList();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Map<String, Object> source, String key) {
        Object value = source == null ? null : source.get(key);
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    static String text(Map<String, Object> source, String key) {
        Object value = source == null ? null : source.get(key);
        return value instanceof String string && !string.isBlank() ? string : null;
    }

    private static int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static Integer integer(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static Integer rounded(Object value) {
        return value instanceof Number number ? (int) Math.round(number.doubleValue()) : null;
    }

    private static Integer hours(Object seconds) {
        return seconds instanceof Number number && number.longValue() > 0
                ? (int) Math.max(1, Math.round(number.longValue() / 3600.0)) : null;
    }

    private static Integer max(Integer current, Integer next) {
        if (next == null) {
            return current;
        }
        return current == null ? next : Math.max(current, next);
    }

    static LocalDate date(Object epochSeconds) {
        return epochSeconds instanceof Number number
                ? Instant.ofEpochSecond(number.longValue()).atZone(ZoneOffset.UTC).toLocalDate() : null;
    }

    private static Integer year(Object epochSeconds) {
        LocalDate date = date(epochSeconds);
        return date == null ? null : date.getYear();
    }
}
