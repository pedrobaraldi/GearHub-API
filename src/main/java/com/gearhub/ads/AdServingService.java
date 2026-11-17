package com.gearhub.ads;

import com.gearhub.auth.RateLimiter;
import com.gearhub.crypto.CryptoService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.net.URI;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Anúncios no site: escolhe o que vai ao ar em cada espaço (aprovado, na vigência, dentro do limite, com criativo
 * que serve no formato), e registra impressões e cliques sem dado pessoal. Cada anúncio servido leva uma
 * assinatura: impressões e cliques só contam para o que o servidor de fato entregou naquele espaço.
 */
@Service
public class AdServingService {

    private static final int IMPRESSIONS_PER_MINUTE = 120;
    private static final int CLICKS_PER_MINUTE = 30;

    @Schema(description = "Anúncio para exibir. Sempre com a etiqueta \"Publicidade\"")
    public record Ad(UUID campaignId, UUID creativeId,
            @Schema(allowableValues = {"banner", "panel", "promo_card", "brand", "institutional"}) String format,
            String advertiser, String title, String description, String imageUrl, String mobileImageUrl, String logoUrl,
            String ctaText, String altText,
            @Schema(description = "Para registrar a impressão e o clique") String signature,
            @Schema(description = "Caminho do clique (acrescente &sid=<sessão>); registra e leva ao destino") String clickPath) {
    }

    @Schema(description = "Anúncios de um espaço")
    public record SlotAds(String slotKey, String format, Integer width, Integer height, String aspectRatio, List<Ad> items) {
    }

    private final JdbcClient jdbc;
    private final CryptoService crypto;
    private final RateLimiter rateLimiter;

    public AdServingService(JdbcClient jdbc, CryptoService crypto, RateLimiter rateLimiter) {
        this.jdbc = jdbc;
        this.crypto = crypto;
        this.rateLimiter = rateLimiter;
    }

    private static final String ELIGIBLE = """
            SELECT c.id AS campaign_id, c.priority AS campaign_priority, cs.priority AS slot_priority,
                   cr.id AS creative_id, cr.title, cr.description, cr.image_url, cr.mobile_image_url, cr.logo_url, cr.cta_text,
                   cr.alt_text, cr.destination_url, a.display_name AS advertiser
            FROM ad_slots s
            JOIN ad_campaign_slots cs ON cs.slot_id = s.id
            JOIN ad_campaigns c ON c.id = cs.campaign_id
            JOIN advertisers a ON a.id = c.advertiser_id
            JOIN ad_creatives cr ON cr.campaign_id = c.id
            WHERE s.id = :slot
              AND c.status = 'approved' AND c.deleted_at IS NULL AND now() >= c.start_at AND now() < c.end_at
              AND a.status = 'active' AND a.deleted_at IS NULL
              AND cr.status = 'active' AND cr.deleted_at IS NULL
              AND (c.impression_limit IS NULL
                   OR (SELECT count(*) FROM ad_events e WHERE e.campaign_id = c.id AND e.event_type = 'impression') < c.impression_limit)
            """;

    /** O que vai ao ar no espaço agora. Espaço inativo ou sem campanha válida volta vazio (o site não mostra nada). */
    @Transactional(readOnly = true)
    public SlotAds serve(String slotKey) {
        Map<String, Object> slot = slot(slotKey);
        if (slot == null) {
            return new SlotAds(slotKey, null, null, null, null, List.of());
        }
        String format = (String) slot.get("format");
        int textLimit = ((Number) slot.get("text_limit")).intValue();
        List<Map<String, Object>> rows = jdbc.sql(ELIGIBLE).param("slot", slot.get("id")).query().listOfRows().stream()
                .filter(row -> AdFormats.fits(format, textLimit, new AdFormats.Content((String) row.get("title"),
                        (String) row.get("description"), (String) row.get("image_url"), (String) row.get("logo_url"))))
                .toList();
        // Um criativo por campanha (sorteado entre os que servem)
        Map<UUID, List<Map<String, Object>>> byCampaign = new LinkedHashMap<>();
        rows.forEach(row -> byCampaign.computeIfAbsent((UUID) row.get("campaign_id"), id -> new ArrayList<>()).add(row));
        List<Map<String, Object>> picks = new ArrayList<>();
        byCampaign.values().forEach(options -> picks.add(options.get(ThreadLocalRandom.current().nextInt(options.size()))));
        // Rotação: embaralha antes de ordenar pela prioridade, então empates se revezam
        if (Boolean.TRUE.equals(slot.get("rotation_enabled"))) {
            Collections.shuffle(picks);
        }
        picks.sort(Comparator.comparingInt((Map<String, Object> row) -> ((Number) row.get("slot_priority")).intValue()
                + ((Number) row.get("campaign_priority")).intValue()).reversed());
        int max = ((Number) slot.get("max_items")).intValue();
        List<Ad> items = picks.stream().limit(max).map(row -> {
            UUID campaign = (UUID) row.get("campaign_id");
            UUID creative = (UUID) row.get("creative_id");
            String signature = sign(campaign, slotKey, creative);
            return new Ad(campaign, creative, format, (String) row.get("advertiser"), (String) row.get("title"),
                    (String) row.get("description"), (String) row.get("image_url"), (String) row.get("mobile_image_url"),
                    (String) row.get("logo_url"), (String) row.get("cta_text"), (String) row.get("alt_text"), signature,
                    "/api/v1/ads/go/" + creative + "?slot=" + slotKey + "&campaign=" + campaign + "&sig=" + signature);
        }).toList();
        return new SlotAds(slotKey, format, (Integer) slot.get("width"), (Integer) slot.get("height"),
                (String) slot.get("aspect_ratio"), items);
    }

    /**
     * Impressão válida (o site manda quando o anúncio ficou visível por 1 s). Conta uma vez por anúncio, espaço e
     * sessão a cada 30 minutos; assinatura errada ou anúncio que não está no ar não conta.
     */
    @Transactional
    public void impression(AdRequests.Impression request) {
        UUID campaign = uuid(request.campaignId());
        UUID creative = uuid(request.creativeId());
        if (!validSignature(campaign, request.slotKey(), creative, request.signature())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Anúncio inválido");
        }
        String session = sessionHash(request.sessionId());
        rateLimiter.check("ad-impression:" + session, IMPRESSIONS_PER_MINUTE);
        Integer slotId = liveSlotId(campaign, request.slotKey(), creative);
        if (slotId == null) {
            return;
        }
        jdbc.sql("""
                INSERT INTO ad_events (campaign_id, slot_id, creative_id, event_type, session_hash)
                SELECT :campaign, :slot, :creative, 'impression', :session
                WHERE NOT EXISTS (SELECT 1 FROM ad_events WHERE creative_id = :creative AND slot_id = :slot AND session_hash = :session
                                    AND event_type = 'impression' AND created_at > now() - interval '30 minutes')
                """)
                .param("campaign", campaign).param("slot", slotId).param("creative", creative).param("session", session).update();
    }

    /** Clique: registra (sem repetir em 10 s na mesma sessão) e devolve o destino do criativo. Não é redirecionamento aberto. */
    @Transactional
    public URI click(UUID creative, String slotKey, String campaignId, String signature, String sessionId) {
        UUID campaign = uuid(campaignId);
        if (slotKey == null || signature == null || !validSignature(campaign, slotKey, creative, signature)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Anúncio não encontrado");
        }
        String destination = jdbc.sql("SELECT destination_url FROM ad_creatives WHERE id = :id AND campaign_id = :campaign")
                .param("id", creative).param("campaign", campaign).query(String.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Anúncio não encontrado"));
        String session = sessionHash(sessionId == null || sessionId.length() < 8 ? "sem-sessao" : sessionId);
        Integer slotId = liveSlotId(campaign, slotKey, creative);
        if (slotId != null && rateLimiter.tryConsume("ad-click:" + session, CLICKS_PER_MINUTE)) {
            jdbc.sql("""
                    INSERT INTO ad_events (campaign_id, slot_id, creative_id, event_type, session_hash)
                    SELECT :campaign, :slot, :creative, 'click', :session
                    WHERE NOT EXISTS (SELECT 1 FROM ad_events WHERE creative_id = :creative AND slot_id = :slot AND session_hash = :session
                                        AND event_type = 'click' AND created_at > now() - interval '10 seconds')
                    """)
                    .param("campaign", campaign).param("slot", slotId).param("creative", creative).param("session", session).update();
        }
        // Mesmo fora do ar (campanha que acabou de encerrar), a pessoa ainda vai para o destino; só não conta
        return URI.create(destination);
    }

    private Map<String, Object> slot(String slotKey) {
        return jdbc.sql("""
                SELECT id, format, width, height, aspect_ratio, text_limit, max_items, rotation_enabled
                FROM ad_slots WHERE slot_key = :key AND status = 'active' AND deleted_at IS NULL
                """).param("key", slotKey).query().listOfRows().stream().findFirst().orElse(null);
    }

    /** O espaço, se o anúncio está mesmo no ar nele agora. */
    private Integer liveSlotId(UUID campaign, String slotKey, UUID creative) {
        Map<String, Object> slot = slot(slotKey);
        if (slot == null) {
            return null;
        }
        boolean live = jdbc.sql(ELIGIBLE + " AND c.id = :campaign AND cr.id = :creative")
                .param("slot", slot.get("id")).param("campaign", campaign).param("creative", creative)
                .query().listOfRows().size() > 0;
        return live ? (Integer) slot.get("id") : null;
    }

    private String sign(UUID campaign, String slotKey, UUID creative) {
        return crypto.blindIndex("ad:" + campaign + ":" + slotKey + ":" + creative).substring(0, 32);
    }

    private boolean validSignature(UUID campaign, String slotKey, UUID creative, String signature) {
        return MessageDigest.isEqual(sign(campaign, slotKey, creative).getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
    }

    /** Só o hash: não dá para voltar ao identificador nem cruzar com a conta. */
    private String sessionHash(String sessionId) {
        return crypto.blindIndex("ad-session:" + sessionId).substring(0, 32);
    }

    private static UUID uuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Anúncio inválido");
        }
    }
}
