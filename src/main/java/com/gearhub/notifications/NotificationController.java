package com.gearhub.notifications;

import com.gearhub.auth.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me/notifications")
@Tag(name = "Notificações", description = "Curtidas, comentários, seguidores e subida de rank")
@SecurityRequirement(name = "session")
public class NotificationController {

    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    @Operation(summary = "Notificações, das mais novas para as mais antigas. `nextCursor` vazio indica o fim")
    public NotificationService.Page page(@AuthenticationPrincipal CurrentUser current,
            @Parameter(description = "Cursor da página anterior") @RequestParam(required = false) Instant before,
            @RequestParam(defaultValue = "20") int size) {
        return notifications.page(current.id(), before, size);
    }

    @GetMapping("/unread-count")
    @Operation(summary = "Quantas ainda não foram vistas, para o contador do sino")
    public Map<String, Integer> unread(@AuthenticationPrincipal CurrentUser current) {
        return Map.of("unread", notifications.unread(current.id()));
    }

    @PostMapping("/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Marca todas como vistas")
    public void markAllRead(@AuthenticationPrincipal CurrentUser current) {
        notifications.markAllRead(current.id());
    }

    @GetMapping("/settings")
    @Operation(summary = "O que a pessoa quer receber")
    public NotificationService.Settings settings(@AuthenticationPrincipal CurrentUser current) {
        return notifications.settings(current.id());
    }

    @PutMapping("/settings")
    @Operation(summary = "Liga ou desliga cada tipo de notificação")
    public NotificationService.Settings saveSettings(@AuthenticationPrincipal CurrentUser current,
            @RequestBody NotificationService.Settings body) {
        return notifications.saveSettings(current.id(), body);
    }
}
