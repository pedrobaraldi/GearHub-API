package com.gearhub.auth;

import com.gearhub.crypto.CryptoService;
import com.gearhub.users.User;
import com.gearhub.users.UserRepository;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

/** Login com Google. O token do Google só é usado aqui e nunca é guardado. */
@Service
public class GoogleAuthService {

    public static final String ERROR_FAILED = "google-falhou";
    public static final String ERROR_UNVERIFIED = "email-nao-verificado";

    public static class GoogleLoginException extends RuntimeException {

        private final String code;

        GoogleLoginException(String code, Throwable cause) {
            super(code, cause);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP = new ParameterizedTypeReference<>() {
    };

    private final GoogleProperties properties;
    private final UserRepository users;
    private final CryptoService crypto;
    private final JdbcClient jdbc;
    private final RestClient http = RestClient.create();

    public GoogleAuthService(GoogleProperties properties, UserRepository users, CryptoService crypto, JdbcClient jdbc) {
        this.properties = properties;
        this.users = users;
        this.crypto = crypto;
        this.jdbc = jdbc;
    }

    public String authorizationUrl(String state) {
        if (!properties.configured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "O login com Google ainda não está disponível. Use seu e-mail e senha.");
        }
        return UriComponentsBuilder.fromUriString(properties.authorizationUri())
                .queryParam("client_id", properties.clientId())
                .queryParam("redirect_uri", properties.redirectUri())
                .queryParam("response_type", "code")
                .queryParam("scope", "openid email profile")
                .queryParam("state", state)
                .encode().toUriString();
    }

    @Transactional
    public User authenticate(String code) {
        Map<String, Object> profile = fetchProfile(code);
        String subject = String.valueOf(profile.get("sub"));
        String subjectHash = crypto.blindIndex("google:" + subject);

        Optional<UUID> linked = jdbc.sql(
                "SELECT user_id FROM oauth_accounts WHERE provider = 'google' AND provider_user_id_hash = :hash")
                .param("hash", subjectHash).query(UUID.class).optional();
        if (linked.isPresent()) {
            return users.findById(linked.get())
                    .filter(user -> user.getDeletedAt() == null)
                    .orElseThrow(() -> new GoogleLoginException(ERROR_FAILED, null));
        }

        // Só liga contas ou cria uma nova quando o próprio Google confirma o e-mail
        if (!Boolean.TRUE.equals(profile.get("email_verified")) || profile.get("email") == null) {
            throw new GoogleLoginException(ERROR_UNVERIFIED, null);
        }
        String email = profile.get("email").toString().strip().toLowerCase(Locale.ROOT);
        String emailHash = crypto.blindIndex(email);
        User user = users.findByEmailHashAndDeletedAtIsNull(emailHash).orElseGet(() -> createUser(profile, email, emailHash));
        user.markEmailVerified();

        jdbc.sql("""
                INSERT INTO oauth_accounts (user_id, provider, provider_user_id_encrypted, provider_user_id_hash)
                VALUES (:user, 'google', :encrypted, :hash)
                """)
                .param("user", user.getId())
                .param("encrypted", crypto.encrypt(subject))
                .param("hash", subjectHash)
                .update();
        return user;
    }

    private User createUser(Map<String, Object> profile, String email, String emailHash) {
        String name = profile.get("name") == null ? email.substring(0, email.indexOf('@')) : profile.get("name").toString();
        User user = new User(uniqueUsername(email), truncate(name), email, emailHash, null);
        return users.saveAndFlush(user);
    }

    private String uniqueUsername(String email) {
        String base = email.substring(0, email.indexOf('@')).replaceAll("[^a-z0-9]", "");
        String candidate = base.length() < 3 ? "usuario" : truncateTo(base, 24);
        while (users.existsByUsername(candidate)) {
            candidate = truncateTo(base.length() < 3 ? "usuario" : base, 20) + UUID.randomUUID().toString().substring(0, 6).replace("-", "");
        }
        return candidate;
    }

    private static String truncate(String name) {
        return truncateTo(name.strip(), 60);
    }

    private static String truncateTo(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private Map<String, Object> fetchProfile(String code) {
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("code", code);
            form.add("client_id", properties.clientId());
            form.add("client_secret", properties.clientSecret());
            form.add("redirect_uri", properties.redirectUri());
            form.add("grant_type", "authorization_code");

            Map<String, Object> token = http.post().uri(properties.tokenUri())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve().body(JSON_MAP);
            return http.get().uri(properties.userInfoUri())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token.get("access_token"))
                    .retrieve().body(JSON_MAP);
        } catch (RestClientException | NullPointerException e) {
            throw new GoogleLoginException(ERROR_FAILED, e);
        }
    }
}
