package com.gearhub.account;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Uma vez por dia, apaga de vez as contas cujo prazo de 30 dias para desistir já passou. */
@Component
public class AccountPurger {

    private static final Logger log = LoggerFactory.getLogger(AccountPurger.class);

    private final AccountService accounts;

    public AccountPurger(AccountService accounts) {
        this.accounts = accounts;
    }

    @Scheduled(cron = "0 15 4 * * *")
    public void purge() {
        int purged = accounts.purgeExpired();
        if (purged > 0) {
            log.info("Contas apagadas de vez após o prazo: {}", purged);
        }
    }
}
