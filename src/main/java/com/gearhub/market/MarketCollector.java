package com.gearhub.market;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Coleta de hora em hora: para cada jogo acompanhado, salva jogadores online, avaliações e preço.
 * O preço só ganha linha nova no histórico quando muda. Uma falha num jogo não para os outros.
 */
@Component
public class MarketCollector {

    private static final Logger log = LoggerFactory.getLogger(MarketCollector.class);

    private final JdbcClient jdbc;
    private final SteamClient steam;
    private final MarketProperties properties;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public MarketCollector(JdbcClient jdbc, SteamClient steam, MarketProperties properties) {
        this.jdbc = jdbc;
        this.steam = steam;
        this.properties = properties;
    }

    // Primeira coleta logo depois de a API subir, em segundo plano, para a aba não começar vazia
    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        collectAll();
    }

    @Scheduled(cron = "0 0 * * * *")
    public void hourly() {
        collectAll();
    }

    public void collectAll() {
        if (!properties.collect() || !running.compareAndSet(false, true)) {
            return;
        }
        try {
            List<Integer> appids = jdbc.sql("SELECT steam_appid FROM market_games ORDER BY steam_appid")
                    .query(Integer.class).list();
            int collected = 0;
            for (int appid : appids) {
                try {
                    if (collect(appid)) {
                        collected++;
                    }
                } catch (RuntimeException e) {
                    log.warn("Coleta da Steam falhou no app {}: {}", appid, e.getMessage());
                }
                pause();
            }
            log.info("Coleta da Steam: {} de {} jogos atualizados", collected, appids.size());
        } finally {
            running.set(false);
        }
    }

    public boolean collect(int appid) {
        var details = steam.details(appid);
        var players = steam.currentPlayers(appid);
        var reviews = steam.reviews(appid);
        if (details.isEmpty() && players.isEmpty()) {
            return false;
        }

        details.ifPresent(d -> {
            SteamClient.Price price = d.price();
            jdbc.sql("""
                    UPDATE market_games SET name = :name, header_image = :image, short_description = :description,
                        developers = :developers, genres = string_to_array(:genres, '|'), release_date = :release,
                        is_free = :free, currency = :currency, initial_cents = :initial, final_cents = :final,
                        discount_percent = :discount, updated_at = now()
                    WHERE steam_appid = :appid
                    """)
                    .param("name", d.name()).param("image", d.headerImage()).param("description", d.shortDescription())
                    .param("developers", d.developers()).param("genres", String.join("|", d.genres()))
                    .param("release", d.releaseDate()).param("free", d.free())
                    .param("currency", price == null ? null : price.currency())
                    .param("initial", price == null ? null : price.initialCents())
                    .param("final", price == null ? null : price.finalCents())
                    .param("discount", price == null ? 0 : price.discountPercent())
                    .param("appid", appid)
                    .update();
            if (price != null) {
                // Só grava quando o preço for diferente do último registrado
                jdbc.sql("""
                        INSERT INTO market_price_snapshots (steam_appid, currency, initial_cents, final_cents, discount_percent)
                        SELECT :appid, :currency, :initial, :final, :discount
                        WHERE NOT EXISTS (
                            SELECT 1 FROM (SELECT final_cents, initial_cents, currency FROM market_price_snapshots
                                           WHERE steam_appid = :appid ORDER BY taken_at DESC LIMIT 1) last
                            WHERE last.final_cents = :final AND last.initial_cents = :initial AND last.currency = :currency)
                        """)
                        .param("appid", appid).param("currency", price.currency())
                        .param("initial", price.initialCents()).param("final", price.finalCents())
                        .param("discount", price.discountPercent())
                        .update();
            }
        });

        players.ifPresent(count -> {
            jdbc.sql("UPDATE market_games SET current_players = :players, updated_at = now() WHERE steam_appid = :appid")
                    .param("players", count).param("appid", appid).update();
            jdbc.sql("INSERT INTO market_player_snapshots (steam_appid, players) VALUES (:appid, :players)")
                    .param("appid", appid).param("players", count).update();
        });

        reviews.ifPresent(r -> jdbc.sql("""
                UPDATE market_games SET review_positive = :positive, review_negative = :negative, review_summary = :summary
                WHERE steam_appid = :appid
                """)
                .param("positive", r.positive()).param("negative", r.negative()).param("summary", r.summary())
                .param("appid", appid).update());
        return true;
    }

    private void pause() {
        try {
            Thread.sleep(properties.pauseMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
