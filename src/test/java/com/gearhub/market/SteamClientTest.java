package com.gearhub.market;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** A Steam devolve as avaliações em JSON, mas com Content-Type text/html. */
class SteamClientTest {

    @Test
    void leAvaliacoesMesmoMarcadasComoHtml() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/appreviews/", exchange -> {
            byte[] body = """
                    {"success":1,"query_summary":{"review_score_desc":"Muito positivas","total_positive":900,"total_negative":100}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            SteamClient steam = new SteamClient(new MarketProperties(false, "br", "brazilian", base, base, 0));
            SteamClient.Reviews reviews = steam.reviews(570).orElseThrow();
            assertThat(reviews.positive()).isEqualTo(900);
            assertThat(reviews.negative()).isEqualTo(100);
            assertThat(reviews.summary()).isEqualTo("Muito positivas");
        } finally {
            server.stop(0);
        }
    }
}
