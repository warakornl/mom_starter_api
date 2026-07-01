package com.momstarter.account;

import com.momstarter.account.dto.AccountResponse;

/**
 * Thrown by {@link AccountService#patchAccount} when the {@code If-Match} version does not
 * match the current account version (api-contract B2 — one concurrency mechanism, PATCH /account).
 *
 * <p>The controller catches this and returns HTTP 409 Conflict with the current authoritative
 * account as the body, so the client can re-pull, re-apply its intent against the fresh record,
 * and retry (mirrors the {@link com.momstarter.pregnancy.StaleVersionException} pattern used by
 * PUT /pregnancy-profile and POST /pregnancy-profile/birth-event).
 */
public class StaleAccountException extends RuntimeException {

    private final AccountResponse currentAccount;

    public StaleAccountException(AccountResponse currentAccount) {
        super("If-Match version does not match the current account version");
        this.currentAccount = currentAccount;
    }

    public AccountResponse getCurrentAccount() {
        return currentAccount;
    }
}
