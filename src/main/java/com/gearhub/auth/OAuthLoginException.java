package com.gearhub.auth;

/** Falha no login social. O código vai para o site, que pede o texto em /auth/errors. */
public class OAuthLoginException extends RuntimeException {

    private final String code;

    public OAuthLoginException(String code, Throwable cause) {
        super(code, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
