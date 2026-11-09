package com.gearhub.users;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/** Respostas de usuário. Hashes nunca saem da API, e o e-mail só vai para o próprio dono. */
public final class UserResponses {

    private UserResponses() {
    }

    @Schema(description = "Perfil público")
    public record PublicProfile(
            @Schema(example = "pedrolm") String username,
            @Schema(example = "Pedro LM") String displayName,
            String bio,
            String avatarUrl,
            String bannerUrl,
            String website,
            String twitter,
            String instagram,
            String youtube,
            @Schema(example = "Cobre") String rankName,
            int level,
            boolean pro,
            Instant createdAt,
            int followerCount,
            int followingCount,
            @Schema(description = "Se quem está logado segue este perfil") boolean followedByMe,
            @Schema(description = "Insígnias exclusivas, na ordem do catálogo") List<BadgeService.Badge> badges) {

        public static PublicProfile from(User user, FollowService.FollowCounts follows, List<BadgeService.Badge> badges) {
            return new PublicProfile(user.getUsername(), user.getDisplayName(), user.getBio(), user.getAvatarUrl(),
                    user.getBannerUrl(), user.getWebsite(), user.getTwitter(), user.getInstagram(), user.getYoutube(),
                    user.getRankName(), user.getLevel(), user.isPro(), user.getCreatedAt(), follows.followers(),
                    follows.following(), follows.followedByMe(), badges);
        }
    }

    @Schema(description = "Dados da própria conta")
    public record Me(
            @Schema(example = "pedrolm") String username,
            @Schema(example = "Pedro LM") String displayName,
            String bio,
            String avatarUrl,
            String bannerUrl,
            String website,
            String twitter,
            String instagram,
            String youtube,
            String email,
            boolean emailVerified,
            String role,
            @Schema(example = "Cobre") String rankName,
            int level,
            boolean pro,
            Instant createdAt) {

        public static Me from(User user) {
            return new Me(user.getUsername(), user.getDisplayName(), user.getBio(), user.getAvatarUrl(),
                    user.getBannerUrl(), user.getWebsite(), user.getTwitter(), user.getInstagram(), user.getYoutube(),
                    user.getEmail(), user.getEmailVerifiedAt() != null, user.getRole(), user.getRankName(),
                    user.getLevel(), user.isPro(), user.getCreatedAt());
        }
    }
}
