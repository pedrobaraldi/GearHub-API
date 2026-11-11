package com.gearhub.users;

import com.gearhub.admin.AuditService;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Edição do próprio perfil. Cada mudança vai para a auditoria com o valor de antes e o de depois. */
@Service
public class ProfileService {

    private final UserRepository users;
    private final RingStyleService ringStyles;
    private final FollowService follows;
    private final BadgeService badges;
    private final AuditService audit;

    public ProfileService(UserRepository users, RingStyleService ringStyles, FollowService follows, BadgeService badges,
            AuditService audit) {
        this.users = users;
        this.ringStyles = ringStyles;
        this.follows = follows;
        this.badges = badges;
        this.audit = audit;
    }

    /** Perfil público. Conta apagada se comporta como inexistente. {@code viewerId} pode ser nulo. */
    @Transactional(readOnly = true)
    public UserResponses.PublicProfile publicProfile(String username, UUID viewerId) {
        User user = users.findByUsernameAndDeletedAtIsNull(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuário não encontrado"));
        return UserResponses.PublicProfile.from(user, follows.counts(user.getId(), viewerId),
                badges.of(user.getId()), ringStyles.effective(user.getId()));
    }

    @Transactional
    public User update(UUID userId, UpdateProfileRequest request) {
        User user = users.findById(userId).orElseThrow();
        Map<String, String> before = snapshot(user);
        if (request.username() != null && !request.username().equals(user.getUsername())) {
            if (users.existsByUsername(request.username())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Esse nome de usuário já está em uso");
            }
            user.changeUsername(request.username());
        }
        String displayName = request.displayName() == null ? user.getDisplayName() : request.displayName().strip();
        user.updateProfile(displayName, merged(request.bio(), user.getBio()));
        user.updateLinks(merged(request.website(), user.getWebsite()),
                withoutAt(merged(request.twitter(), user.getTwitter())),
                withoutAt(merged(request.instagram(), user.getInstagram())),
                merged(request.youtube(), user.getYoutube()));
        if (request.ringStyle() != null) {
            user.chooseRing(ringStyles.validated(user.getId(), request.ringStyle().strip()));
        }
        // Histórico do perfil: só o que mudou, com o valor de antes e o de depois
        Map<String, String> after = snapshot(user);
        Map<String, Object> changes = new LinkedHashMap<>();
        before.forEach((field, old) -> {
            if (!Objects.equals(old, after.get(field))) {
                Map<String, String> change = new HashMap<>();
                change.put("de", old);
                change.put("para", after.get(field));
                changes.put(field, change);
            }
        });
        if (!changes.isEmpty()) {
            audit.record(user.getId(), "profile.update", "user", user.getId(), changes);
        }
        return user;
    }

    private static Map<String, String> snapshot(User user) {
        Map<String, String> profile = new LinkedHashMap<>();
        profile.put("usuario", user.getUsername());
        profile.put("nome", user.getDisplayName());
        profile.put("bio", user.getBio());
        profile.put("site", user.getWebsite());
        profile.put("twitter", user.getTwitter());
        profile.put("instagram", user.getInstagram());
        profile.put("youtube", user.getYoutube());
        profile.put("contorno", user.getRingStyle());
        return profile;
    }

    private static String merged(String sent, String current) {
        if (sent == null) {
            return current;
        }
        return sent.isBlank() ? null : sent.strip();
    }

    private static String withoutAt(String handle) {
        return handle == null ? null : handle.replaceFirst("^@", "");
    }
}
