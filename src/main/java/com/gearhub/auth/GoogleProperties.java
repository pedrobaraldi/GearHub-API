package com.gearhub.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("gearhub.google")
public record GoogleProperties(
        String clientId,
        String clientSecret,
        String authorizationUri,
        String tokenUri,
        String userInfoUri,
        String siteUrl) {

    public boolean configured() {
        return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
    }

    public String redirectUri() {
        return siteUrl + "/api/v1/auth/google/callback";
    }
}
