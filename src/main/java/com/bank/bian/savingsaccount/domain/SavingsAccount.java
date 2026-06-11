package com.bank.bian.savingsaccount.domain;

import java.time.Instant;

/**
 * Control record made real: "Savings Account Facility Fulfillment Arrangement".
 *
 * What makes savings different from current (the rules live in
 * SavingsAccountService):
 *  - NO overdraft — balance can never go below minBalanceMinor (>= 0)
 *  - interest accrues (interestRateBp, simple daily accrual) and is
 *    capitalized into the balance on demand
 *  - withdrawals are capped per calendar month
 *
 * Money: long minor units. Interest rate: basis points (350 = 3.50% p.a.).
 */
public class SavingsAccount {

    public enum Status { PENDING_KYC, ACTIVE, BLOCKED, REJECTED, CLOSED }

    private String accountId;
    private String customerReference;
    private String currency;
    private long balanceMinor;
    private long accruedInterestMinor;
    private int interestRateBp;
    private long minBalanceMinor;
    private Status status = Status.PENDING_KYC;
    private Instant openedAt;
    private Instant updatedAt;

    public static SavingsAccount open(String accountId, String customerReference, String currency,
                                      int interestRateBp, long minBalanceMinor, Instant now) {
        SavingsAccount a = new SavingsAccount();
        a.accountId = accountId;
        a.customerReference = customerReference;
        a.currency = currency;
        a.interestRateBp = interestRateBp;
        a.minBalanceMinor = minBalanceMinor;
        a.openedAt = now;
        a.updatedAt = now;
        return a;
    }

    public boolean isTerminal() {
        return status == Status.CLOSED || status == Status.REJECTED;
    }

    public String getAccountId() { return accountId; }
    public String getCustomerReference() { return customerReference; }
    public String getCurrency() { return currency; }
    public long getBalanceMinor() { return balanceMinor; }
    public void setBalanceMinor(long balanceMinor) { this.balanceMinor = balanceMinor; }
    public long getAccruedInterestMinor() { return accruedInterestMinor; }
    public void setAccruedInterestMinor(long accruedInterestMinor) { this.accruedInterestMinor = accruedInterestMinor; }
    public int getInterestRateBp() { return interestRateBp; }
    public long getMinBalanceMinor() { return minBalanceMinor; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public Instant getOpenedAt() { return openedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
