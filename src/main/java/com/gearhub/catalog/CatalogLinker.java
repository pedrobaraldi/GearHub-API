package com.gearhub.catalog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Liga os jogos acompanhados na Steam às suas páginas no catálogo, para os cards da aba Games abrirem a
 * página completa. Uma chamada ao IGDB para cada 100 jogos ainda sem ligação; os já ligados não custam nada.
 */
@Component
public class CatalogLinker {

    private static final Logger log = LoggerFactory.getLogger(CatalogLinker.class);

    private final CatalogService catalog;

    public CatalogLinker(CatalogService catalog) {
        this.catalog = catalog;
    }

    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        link();
    }

    // Meia hora depois da coleta da Steam, que roda na hora cheia
    @Scheduled(cron = "0 30 * * * *")
    public void hourly() {
        link();
    }

    private void link() {
        int linked = catalog.linkMarketGames();
        if (linked > 0) {
            log.info("Catálogo: {} jogos da Steam ligados às suas páginas", linked);
        }
    }
}
