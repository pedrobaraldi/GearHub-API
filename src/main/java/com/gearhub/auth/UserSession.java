package com.gearhub.auth;

import com.gearhub.crypto.EncryptedStringConverter;
import com.gearhub.users.User;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sessions")
public class UserSession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "token_hash")
    private String tokenHash;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "ip_encrypted")
    private String ip;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "user_agent_encrypted")
    private String userAgent;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt = Instant.now();

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    protected UserSession() {
    }

    public UserSession(User user, String tokenHash, String ip, String userAgent, Instant expiresAt) {
        this.user = user;
        this.tokenHash = tokenHash;
        this.ip = ip;
        this.userAgent = userAgent;
        this.expiresAt = expiresAt;
    }

    public UUID getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public boolean isExpired() {
        return expiresAt.isBefore(Instant.now());
    }
}
