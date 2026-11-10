package com.gearhub.users;

import com.gearhub.admin.AuditService;
import com.gearhub.auth.CurrentUser;
import com.gearhub.auth.AdminAccess;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "Usuários", description = "Perfis públicos e dados da própria conta")
public class UserController {

    private final UserRepository users;
    private final AvatarService avatars;
    private final FollowService follows;
    private final BadgeService badges;
    private final RingStyleService ringStyles;
    private final AdminAccess adminAccess;
    private final AuditService audit;

    public UserController(UserRepository users, AvatarService avatars, FollowService follows, BadgeService badges,
            RingStyleService ringStyles, AdminAccess adminAccess, AuditService audit) {
        this.audit = audit;
        this.adminAccess = adminAccess;
        this.users = users;
        this.avatars = avatars;
        this.follows = follows;
        this.badges = badges;
        this.ringStyles = ringStyles;
    }

    @GetMapping("/{username}")
    @Transactional(readOnly = true)
    @Operation(summary = "Busca o perfil público de um usuário")
    @ApiResponse(responseCode = "404", description = "Usuário não encontrado")
    public UserResponses.PublicProfile profile(
            @AuthenticationPrincipal CurrentUser current,
            @PathVariable String username) {
        User user = users.findByUsernameAndDeletedAtIsNull(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuário não encontrado"));
        return UserResponses.PublicProfile.from(user, follows.counts(user.getId(), current == null ? null : current.id()),
                badges.of(user.getId()), ringStyles.effective(user.getId()));
    }

    @PutMapping("/{username}/badges/{key}")
    @Operation(summary = "Dá uma insígnia exclusiva (só admin)", security = @SecurityRequirement(name = "session"))
    @ApiResponse(responseCode = "403", description = "Só admin dá insígnias")
    public List<BadgeService.Badge> grantBadge(
            @AuthenticationPrincipal CurrentUser current,
            @PathVariable String username,
            @PathVariable String key) {
        requireAdmin(current);
        return badges.set(current.id(), username, key, true);
    }

    @DeleteMapping("/{username}/badges/{key}")
    @Operation(summary = "Tira uma insígnia exclusiva (só admin)", security = @SecurityRequirement(name = "session"))
    @ApiResponse(responseCode = "403", description = "Só admin tira insígnias")
    public List<BadgeService.Badge> revokeBadge(
            @AuthenticationPrincipal CurrentUser current,
            @PathVariable String username,
            @PathVariable String key) {
        requireAdmin(current);
        return badges.set(current.id(), username, key, false);
    }

    private void requireAdmin(CurrentUser current) {
        adminAccess.require(current, "Só admin pode mudar insígnias");
    }

    @PutMapping("/{username}/follow")
    @Operation(summary = "Segue um usuário", security = @SecurityRequirement(name = "session"))
    public FollowService.FollowCounts follow(
            @AuthenticationPrincipal CurrentUser current,
            @PathVariable String username) {
        return follows.setFollowing(current.id(), username, true);
    }

    @DeleteMapping("/{username}/follow")
    @Operation(summary = "Deixa de seguir um usuário", security = @SecurityRequirement(name = "session"))
    public FollowService.FollowCounts unfollow(
            @AuthenticationPrincipal CurrentUser current,
            @PathVariable String username) {
        return follows.setFollowing(current.id(), username, false);
    }

    @PostMapping(path = "/me/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Troca a foto de perfil (JPG, PNG ou WebP, até 2 MB)", security = @SecurityRequirement(name = "session"))
    public UserResponses.Me changeAvatar(
            @AuthenticationPrincipal CurrentUser current,
            @RequestPart("file") MultipartFile file) {
        return UserResponses.Me.from(avatars.replace(current.id(), file));
    }

    @DeleteMapping("/me/avatar")
    @Operation(summary = "Remove a foto de perfil", security = @SecurityRequirement(name = "session"))
    public UserResponses.Me removeAvatar(@AuthenticationPrincipal CurrentUser current) {
        return UserResponses.Me.from(avatars.removeAvatar(current.id()));
    }

    @PostMapping(path = "/me/banner", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Troca o banner do perfil (JPG, PNG ou WebP, até 2 MB)", security = @SecurityRequirement(name = "session"))
    public UserResponses.Me changeBanner(
            @AuthenticationPrincipal CurrentUser current,
            @RequestPart("file") MultipartFile file) {
        return UserResponses.Me.from(avatars.replaceBanner(current.id(), file));
    }

    @DeleteMapping("/me/banner")
    @Operation(summary = "Remove o banner do perfil", security = @SecurityRequirement(name = "session"))
    public UserResponses.Me removeBanner(@AuthenticationPrincipal CurrentUser current) {
        return UserResponses.Me.from(avatars.removeBanner(current.id()));
    }

    @PatchMapping("/me")
    @Transactional
    @Operation(summary = "Atualiza o próprio perfil", security = @SecurityRequirement(name = "session"))
    public UserResponses.Me update(
            @AuthenticationPrincipal CurrentUser current,
            @Valid @RequestBody UpdateProfileRequest request) {
        User user = users.findById(current.id()).orElseThrow();
        Map<String, String> before = profileOf(user);
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
        Map<String, String> after = profileOf(user);
        Map<String, Object> changes = new java.util.LinkedHashMap<>();
        before.forEach((field, old) -> {
            if (!java.util.Objects.equals(old, after.get(field))) {
                Map<String, String> change = new java.util.HashMap<>();
                change.put("de", old);
                change.put("para", after.get(field));
                changes.put(field, change);
            }
        });
        if (!changes.isEmpty()) {
            audit.record(user.getId(), "profile.update", "user", user.getId(), changes);
        }
        return UserResponses.Me.from(user);
    }

    private static Map<String, String> profileOf(User user) {
        Map<String, String> profile = new java.util.LinkedHashMap<>();
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
