package com.gearhub.users;

import com.gearhub.auth.CurrentUser;
import com.gearhub.auth.AdminAccess;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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

@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "Usuários", description = "Perfis públicos e dados da própria conta")
public class UserController {

    private final ProfileService profiles;
    private final AvatarService avatars;
    private final FollowService follows;
    private final BadgeService badges;
    private final AdminAccess adminAccess;

    public UserController(ProfileService profiles, AvatarService avatars, FollowService follows, BadgeService badges,
            AdminAccess adminAccess) {
        this.profiles = profiles;
        this.avatars = avatars;
        this.follows = follows;
        this.badges = badges;
        this.adminAccess = adminAccess;
    }

    @GetMapping("/{username}")
    @Operation(summary = "Busca o perfil público de um usuário")
    @ApiResponse(responseCode = "404", description = "Usuário não encontrado")
    public UserResponses.PublicProfile profile(
            @AuthenticationPrincipal CurrentUser current,
            @PathVariable String username) {
        return profiles.publicProfile(username, current == null ? null : current.id());
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
    @Operation(summary = "Atualiza o próprio perfil", security = @SecurityRequirement(name = "session"))
    public UserResponses.Me update(
            @AuthenticationPrincipal CurrentUser current,
            @Valid @RequestBody UpdateProfileRequest request) {
        return UserResponses.Me.from(profiles.update(current.id(), request));
    }
}
