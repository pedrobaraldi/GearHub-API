package com.gearhub.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("gearhub.crypto")
public record CryptoProperties(String aesKey, String hmacKey) {
}
