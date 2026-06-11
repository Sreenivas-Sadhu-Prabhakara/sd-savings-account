package com.bank.bian.savingsaccount.domain;

import java.time.Instant;

/** Immutable posting; amountMinor signed (credits +, debits -). */
public record AccountTransaction(
        String transactionId,
        String accountId,
        Type type,
        long amountMinor,
        long balanceAfterMinor,
        String reference,
        Instant postedAt
) {
    public enum Type { DEPOSIT, WITHDRAWAL, INTEREST, CHEQUE_CREDIT }
}
