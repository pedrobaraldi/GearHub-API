package com.gearhub.reviews;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Onde a página anterior parou: data e id da última publicação. Só a data não basta, porque duas publicações
 * no mesmo instante fariam uma delas ser pulada. Vai para o navegador como texto opaco ({@code data_id}).
 * {@code id} nulo é o formato antigo, só com a data: ainda aceito, para links já abertos.
 */
public record FeedCursor(Instant createdAt, UUID id) {

    /**
     * Ordem do feed: mais nova primeiro e, no mesmo instante, maior id primeiro. O id é comparado como texto,
     * que é a mesma ordem do tipo uuid do Postgres (o {@link UUID#compareTo} do Java compara com sinal e difere).
     */
    public static final Comparator<ContentResponses.FeedItem> NEWEST_FIRST = Comparator
            .comparing(ContentResponses.FeedItem::createdAt)
            .thenComparing(item -> item.id().toString())
            .reversed();

    public static FeedCursor of(ContentResponses.FeedItem item) {
        return new FeedCursor(item.createdAt(), item.id());
    }

    /** Nulo ou vazio é a primeira página. */
    public static FeedCursor parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            int split = text.indexOf('_');
            if (split < 0) {
                return new FeedCursor(Instant.parse(text.strip()), null);
            }
            return new FeedCursor(Instant.parse(text.substring(0, split)), UUID.fromString(text.substring(split + 1)));
        } catch (DateTimeParseException | IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Página inválida. Atualize o feed.");
        }
    }

    public String encode() {
        return id == null ? createdAt.toString() : createdAt + "_" + id;
    }
}
