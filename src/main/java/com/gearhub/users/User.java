package com.gearhub.users;

import com.gearhub.crypto.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String username;

    @Column(name = "display_name")
    private String displayName;

    private String bio;

    @Column(name = "avatar_url")
    private String avatarUrl;

    @Column(name = "banner_url")
    private String bannerUrl;

    private String website;

    private String twitter;

    private String instagram;

    private String youtube;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "email_encrypted")
    private String email;

    @Column(name = "email_hash")
    private String emailHash;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    @Column(name = "password_hash")
    private String passwordHash;

    private String role = "user";

    // Nível e patente são mantidos pelo banco: ver a migração V14
    @Column(name = "rank_name", updatable = false)
    private String rankName = "Cobre";

    @Column(updatable = false)
    private int level = 1;

    @Column(name = "is_pro")
    private boolean pro;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected User() {
    }

    public User(String username, String displayName, String email, String emailHash, String passwordHash) {
        this.username = username;
        this.displayName = displayName;
        this.email = email;
        this.emailHash = emailHash;
        this.passwordHash = passwordHash;
    }

    public void changePassword(String passwordHash) {
        this.passwordHash = passwordHash;
        this.updatedAt = Instant.now();
    }

    public void markEmailVerified() {
        if (emailVerifiedAt == null) {
            emailVerifiedAt = Instant.now();
        }
    }

    public void changeAvatar(String avatarUrl) {
        this.avatarUrl = avatarUrl;
        this.updatedAt = Instant.now();
    }

    public void changeBanner(String bannerUrl) {
        this.bannerUrl = bannerUrl;
        this.updatedAt = Instant.now();
    }

    public void changeUsername(String username) {
        this.username = username;
        this.updatedAt = Instant.now();
    }

    public void updateLinks(String website, String twitter, String instagram, String youtube) {
        this.website = website;
        this.twitter = twitter;
        this.instagram = instagram;
        this.youtube = youtube;
        this.updatedAt = Instant.now();
    }

    public String getBannerUrl() {
        return bannerUrl;
    }

    public String getWebsite() {
        return website;
    }

    public String getTwitter() {
        return twitter;
    }

    public String getInstagram() {
        return instagram;
    }

    public String getYoutube() {
        return youtube;
    }

    public void updateProfile(String displayName, String bio) {
        this.displayName = displayName;
        this.bio = bio;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getBio() {
        return bio;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public String getEmail() {
        return email;
    }

    public Instant getEmailVerifiedAt() {
        return emailVerifiedAt;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getRole() {
        return role;
    }

    public String getRankName() {
        return rankName;
    }

    public int getLevel() {
        return level;
    }

    public boolean isPro() {
        return pro;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
