package com.gearhub.auth;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Ferramentas de administração: só admin, e só com a verificação em duas etapas ativa. */
@Component
public class AdminAccess {

    private final TwoFactorService twoFactor;

    public AdminAccess(TwoFactorService twoFactor) {
        this.twoFactor = twoFactor;
    }

    public boolean allowed(CurrentUser current) {
        return current != null && "admin".equals(current.role()) && twoFactor.isEnabled(current.id());
    }

    public void require(CurrentUser current, String forbiddenMessage) {
        if (current == null || !"admin".equals(current.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, forbiddenMessage);
        }
        // Conta de admin invadida estraga o site inteiro: as ferramentas pedem a verificação em duas etapas
        if (!twoFactor.isEnabled(current.id())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Ative a verificação em duas etapas para usar as ferramentas de administração.");
        }
    }
}
