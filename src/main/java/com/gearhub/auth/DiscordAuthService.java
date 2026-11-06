package com.gearhub.auth;

import com.gearhub.auth.OAuthAccountService.OAuthProfile;
import com.gearhub.users.User;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

/** Login com Discord. O token do Discord só é usado aqui e nunca é guardado. */
@Service
public class DiscordAuthService {

    public static final String ERROR_FAILED = "discord-falhou";
    public static final String ERROR_UNVERIFIED = "discord-email-nao-verificado";

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP = new ParameterizedTypeReference<>() {
    };

    private final DiscordProperties properties;
    private final OAuthAccountService accounts;
    private final RestClient http = RestClient.create();

    public DiscordAuthService(DiscordProperties properties, OAuthAccountService accounts) {
        this.properties = properties;
        this.accounts = accounts;
    }

    public String authorizationUrl(String state) {
        if (!properties.configured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "O login com Discord ainda não está disponível. Use seu e-mail e senha.");
        }
        return UriComponentsBuilder.fromUriString(properties.authorizationUri())
                .queryParam("client_id", properties.clientId())
                .queryParam("redirect_uri", properties.redirectUri())
                .queryParam("response_type", "code")
                .queryParam("scope", "identify email")
                .queryParam("state", state)
                .encode().toUriString();
    }

    public User authenticate(String code) {
        Map<String, Object> profile = fetchProfile(code);
        String name = profile.get("global_name") != null
                ? profile.get("global_name").toString()
                : (String) profile.get("username");
        return accounts.resolve(new OAuthProfile("discord", String.valueOf(profile.get("id")),
                (String) profile.get("email"), Boolean.TRUE.equals(profile.get("verified")),
                name, ERROR_FAILED, ERROR_UNVERIFIED));
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
            throw new OAuthLoginException(ERROR_FAILED, e);
        }
    }
}
