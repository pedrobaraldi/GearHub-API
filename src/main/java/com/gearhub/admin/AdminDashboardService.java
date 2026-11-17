package com.gearhub.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Painel inicial do admin: série diária para os gráficos e um resumo de cada área do site. */
@Service
public class AdminDashboardService {

    /** Os dias contam no horário de Brasília, como a equipe enxerga o calendário. */
    private static final String ZONE = "America/Sao_Paulo";

    @Schema(description = "Um dia da série (horário de Brasília)")
    public record Day(LocalDate date, int newUsers, int posts, int reviews, int comments, int reports,
            int storeClicks, int adImpressions, int adClicks) {
    }

    public record Community(int users, int newUsersLast7Days,
            @Schema(description = "Contas com sessão usada nos últimos 7 dias") int activeUsersLast7Days,
            int postsLast7Days, int reviewsLast7Days, int commentsLast7Days,
            @Schema(description = "Contas no prazo de 30 dias para desistir da exclusão") int accountsPendingDeletion) {
    }

    public record ReasonCount(String reason, int count) {
    }

    public record Moderation(int pendingReports, int reportedItems, int reportsLast7Days, int reviewedLast7Days,
            @Schema(description = "Denúncias pendentes por motivo, da maior para a menor") List<ReasonCount> pendingByReason) {
    }

    public record Marketplace(@Schema(description = "Pedidos de loja esperando a equipe") int applicationsToReview,
            int activeStores, int activeProducts, int clicksLast7Days) {
    }

    public record Ads(@Schema(description = "Aprovadas e no período de veiculação") int runningCampaigns,
            int campaignsAwaitingApproval, int impressionsLast7Days, int clicksLast7Days) {
    }

    public record Dashboard(List<Day> days, Community community, Moderation moderation, Marketplace marketplace, Ads ads) {
    }

    private final JdbcClient jdbc;

    public AdminDashboardService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Dashboard dashboard(int days) {
        int span = Math.max(7, Math.min(days, 90));
        return new Dashboard(series(span), community(), moderation(), marketplace(), ads());
    }

    private List<Day> series(int span) {
        // Uma consulta por tabela agrupada por dia; dias sem nada aparecem com zero
        return jdbc.sql("""
                WITH days AS (
                    SELECT generate_series((now() AT TIME ZONE :zone)::date - (:span - 1), (now() AT TIME ZONE :zone)::date, interval '1 day')::date AS day
                ), since AS (SELECT ((now() AT TIME ZONE :zone)::date - (:span - 1))::timestamp AT TIME ZONE :zone AS at),
                u AS (SELECT (created_at AT TIME ZONE :zone)::date AS day, count(*) AS n FROM users, since
                      WHERE created_at >= since.at GROUP BY 1),
                p AS (SELECT (created_at AT TIME ZONE :zone)::date AS day, count(*) AS n FROM posts, since
                      WHERE deleted_at IS NULL AND created_at >= since.at GROUP BY 1),
                r AS (SELECT (created_at AT TIME ZONE :zone)::date AS day, count(*) AS n FROM reviews, since
                      WHERE deleted_at IS NULL AND created_at >= since.at GROUP BY 1),
                c AS (SELECT (created_at AT TIME ZONE :zone)::date AS day, count(*) AS n FROM comments, since
                      WHERE deleted_at IS NULL AND created_at >= since.at GROUP BY 1),
                rep AS (SELECT (created_at AT TIME ZONE :zone)::date AS day, count(*) AS n FROM reports, since
                      WHERE created_at >= since.at GROUP BY 1),
                sc AS (SELECT (created_at AT TIME ZONE :zone)::date AS day, count(*) AS n FROM click_events, since
                      WHERE created_at >= since.at GROUP BY 1),
                ad AS (SELECT (created_at AT TIME ZONE :zone)::date AS day,
                              count(*) FILTER (WHERE event_type = 'impression') AS impressions,
                              count(*) FILTER (WHERE event_type = 'click') AS clicks
                       FROM ad_events, since WHERE created_at >= since.at GROUP BY 1)
                SELECT days.day, coalesce(u.n, 0) AS users, coalesce(p.n, 0) AS posts, coalesce(r.n, 0) AS reviews,
                       coalesce(c.n, 0) AS comments, coalesce(rep.n, 0) AS reports, coalesce(sc.n, 0) AS store_clicks,
                       coalesce(ad.impressions, 0) AS impressions, coalesce(ad.clicks, 0) AS ad_clicks
                FROM days
                LEFT JOIN u USING (day) LEFT JOIN p USING (day) LEFT JOIN r USING (day) LEFT JOIN c USING (day)
                LEFT JOIN rep USING (day) LEFT JOIN sc USING (day) LEFT JOIN ad USING (day)
                ORDER BY days.day
                """)
                .param("zone", ZONE).param("span", span)
                .query((rs, row) -> new Day(rs.getObject("day", LocalDate.class), rs.getInt("users"), rs.getInt("posts"),
                        rs.getInt("reviews"), rs.getInt("comments"), rs.getInt("reports"), rs.getInt("store_clicks"),
                        rs.getInt("impressions"), rs.getInt("ad_clicks")))
                .list();
    }

    private Community community() {
        return jdbc.sql("""
                SELECT (SELECT count(*) FROM users WHERE deleted_at IS NULL) AS users,
                       (SELECT count(*) FROM users WHERE deleted_at IS NULL AND created_at > now() - interval '7 days') AS new_users,
                       (SELECT count(DISTINCT user_id) FROM sessions WHERE last_seen_at > now() - interval '7 days') AS active_users,
                       (SELECT count(*) FROM posts WHERE deleted_at IS NULL AND created_at > now() - interval '7 days') AS posts,
                       (SELECT count(*) FROM reviews WHERE deleted_at IS NULL AND created_at > now() - interval '7 days') AS reviews,
                       (SELECT count(*) FROM comments WHERE deleted_at IS NULL AND created_at > now() - interval '7 days') AS comments,
                       (SELECT count(*) FROM users WHERE deleted_at IS NOT NULL) AS pending_deletion
                """)
                .query((rs, row) -> new Community(rs.getInt("users"), rs.getInt("new_users"), rs.getInt("active_users"),
                        rs.getInt("posts"), rs.getInt("reviews"), rs.getInt("comments"), rs.getInt("pending_deletion")))
                .single();
    }

    private Moderation moderation() {
        List<ReasonCount> reasons = jdbc.sql("""
                SELECT reason, count(*) AS n FROM reports WHERE status = 'pending' GROUP BY reason ORDER BY n DESC, reason
                """)
                .query((rs, row) -> new ReasonCount(rs.getString("reason"), rs.getInt("n"))).list();
        return jdbc.sql("""
                SELECT (SELECT count(*) FROM reports WHERE status = 'pending') AS pending,
                       (SELECT count(DISTINCT (target_kind, target_id)) FROM reports WHERE status = 'pending') AS items,
                       (SELECT count(*) FROM reports WHERE created_at > now() - interval '7 days') AS received,
                       (SELECT count(*) FROM reports WHERE reviewed_at > now() - interval '7 days') AS reviewed
                """)
                .query((rs, row) -> new Moderation(rs.getInt("pending"), rs.getInt("items"), rs.getInt("received"),
                        rs.getInt("reviewed"), reasons))
                .single();
    }

    private Marketplace marketplace() {
        return jdbc.sql("""
                SELECT (SELECT count(*) FROM partner_applications WHERE status IN ('pending', 'in_review', 'awaiting_info')) AS to_review,
                       (SELECT count(*) FROM partner_stores WHERE status = 'active' AND deleted_at IS NULL) AS stores,
                       (SELECT count(*) FROM store_products sp JOIN partner_stores s ON s.id = sp.store_id
                        WHERE sp.status = 'active' AND sp.deleted_at IS NULL AND sp.removed_at IS NULL
                          AND s.status = 'active' AND s.deleted_at IS NULL) AS products,
                       (SELECT count(*) FROM click_events WHERE created_at > now() - interval '7 days') AS clicks
                """)
                .query((rs, row) -> new Marketplace(rs.getInt("to_review"), rs.getInt("stores"), rs.getInt("products"),
                        rs.getInt("clicks")))
                .single();
    }

    private Ads ads() {
        return jdbc.sql("""
                SELECT (SELECT count(*) FROM ad_campaigns WHERE status = 'approved' AND deleted_at IS NULL
                          AND now() BETWEEN start_at AND end_at) AS running,
                       (SELECT count(*) FROM ad_campaigns WHERE status = 'pending_approval' AND deleted_at IS NULL) AS awaiting,
                       (SELECT count(*) FROM ad_events WHERE event_type = 'impression' AND created_at > now() - interval '7 days') AS impressions,
                       (SELECT count(*) FROM ad_events WHERE event_type = 'click' AND created_at > now() - interval '7 days') AS clicks
                """)
                .query((rs, row) -> new Ads(rs.getInt("running"), rs.getInt("awaiting"), rs.getInt("impressions"),
                        rs.getInt("clicks")))
                .single();
    }
}
