package com.gearhub.account;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Uma vez por dia, encerra em definitivo as contas cujo prazo de 30 dias para desistir já passou. */
@Component
public class AccountCloser {

    private static final Logger log = LoggerFactory.getLogger(AccountCloser.class);

    private final AccountService accounts;

    public AccountCloser(AccountService accounts) {
        this.accounts = accounts;
    }

    @Scheduled(cron = "0 15 4 * * *")
    public void close() {
        int closed = accounts.closeExpired();
        if (closed > 0) {
            log.info("Contas encerradas em definitivo após o prazo: {}", closed);
        }
    }
}
