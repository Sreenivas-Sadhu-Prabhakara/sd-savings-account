package com.bank.bian.savingsaccount.domain;

import com.bank.bian.savingsaccount.events.DomainEvent;
import com.bank.bian.savingsaccount.events.EventPublisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Business rules for the Savings Account service domain.
 *
 *  - NO overdraft: a withdrawal may not take the balance below the account's
 *    minBalanceMinor. There is no negative balance, ever.
 *  - Monthly withdrawal cap (bian.savings.monthly-withdrawal-cap, default 6 —
 *    the classic savings-product limit): the Nth+1 withdrawal in a calendar
 *    month (UTC) is rejected.
 *  - Interest: simple daily accrual at interestRateBp (basis points p.a.),
 *    floor-rounded: balance * rateBp / 10_000 / 365. Accrues into
 *    accruedInterestMinor; "capitalize" moves it into the balance as an
 *    INTEREST posting. Accrual only on ACTIVE accounts with positive balance.
 *  - KYC gating, blocking, and closing follow the same semantics as
 *    Current Account (credits allowed while BLOCKED, close at zero balance —
 *    including zero accrued interest: capitalize or forfeit first).
 *
 * Clock is injected so interest and the monthly cap are testable.
 */
@Service
public class SavingsAccountService {

    public static final String TOPIC_ACCOUNTS = "bian.accounts.savings-account";
    public static final String TOPIC_KYC = "bian.kyc.check";

    private final AccountRepository repository;
    private final EventPublisher events;
    private final boolean kycAutoApprove;
    private final KycGateway kycGateway;
    private final int monthlyWithdrawalCap;
    private final Clock clock;
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    @Autowired
    public SavingsAccountService(AccountRepository repository,
                                 EventPublisher events,
                                 KycGateway kycGateway,
                                 @Value("${bian.kyc.auto-approve:true}") boolean kycAutoApprove,
                                 @Value("${bian.savings.monthly-withdrawal-cap:6}") int monthlyWithdrawalCap) {
        this(repository, events, kycGateway, kycAutoApprove, monthlyWithdrawalCap, Clock.systemUTC());
    }

    public SavingsAccountService(AccountRepository repository, EventPublisher events,
                                 boolean kycAutoApprove, int monthlyWithdrawalCap, Clock clock) {
        this(repository, events, KycGateway.NONE, kycAutoApprove, monthlyWithdrawalCap, clock);
    }

    public SavingsAccountService(AccountRepository repository, EventPublisher events,
                                 KycGateway kycGateway, boolean kycAutoApprove,
                                 int monthlyWithdrawalCap, Clock clock) {
        this.repository = repository;
        this.events = events;
        this.kycGateway = kycGateway;
        this.kycAutoApprove = kycAutoApprove;
        this.monthlyWithdrawalCap = monthlyWithdrawalCap;
        this.clock = clock;
    }

    // ── lifecycle ────────────────────────────────────────────────────────────

    public SavingsAccount open(String customerReference, String currency,
                               int interestRateBp, long minBalanceMinor) {
        if (customerReference == null || customerReference.isBlank()) {
            throw DomainException.invalid("CUSTOMER_REQUIRED", "customerReference is required");
        }
        if (currency == null || !currency.matches("[A-Z]{3}")) {
            throw DomainException.invalid("CURRENCY_INVALID", "currency must be an ISO 4217 code, e.g. INR");
        }
        if (interestRateBp < 0 || interestRateBp > 2_000) {
            throw DomainException.invalid("RATE_OUT_OF_RANGE", "interestRateBp must be 0..2000 (0–20%)");
        }
        if (minBalanceMinor < 0) {
            throw DomainException.invalid("MIN_BALANCE_NEGATIVE", "minBalanceMinor must be >= 0");
        }

        SavingsAccount account = SavingsAccount.open("SA-" + UUID.randomUUID(), customerReference,
                currency, interestRateBp, minBalanceMinor, clock.instant());
        repository.save(account);

        events.publish(DomainEvent.of(TOPIC_KYC, "kyc.check.requested", Map.of(
                "accountId", account.getAccountId(), "customerReference", customerReference)));
        if (kycGateway.isActive()) {
            // 2d-ii: real KYC wired — dispatch and stay PENDING_KYC for the callback.
            boolean delivered = kycGateway.requestCheck(account.getAccountId(), customerReference);
            events.publish(DomainEvent.of(TOPIC_KYC,
                    delivered ? "kyc.check.dispatched" : "kyc.check.dispatch-failed",
                    Map.of("accountId", account.getAccountId())));
        } else if (kycAutoApprove) {
            account.setStatus(SavingsAccount.Status.ACTIVE);
            account.setUpdatedAt(clock.instant());
            repository.save(account);
            events.publish(DomainEvent.of(TOPIC_KYC, "kyc.assessment.auto-approved", Map.of(
                    "accountId", account.getAccountId(),
                    "note", "Phase 2a: bian.kyc.auto-approve=true until KYC choreography is live")));
        }
        events.publish(DomainEvent.of(TOPIC_ACCOUNTS, "account.opened", Map.of(
                "accountId", account.getAccountId(), "currency", currency,
                "interestRateBp", interestRateBp, "status", account.getStatus().name())));
        return account;
    }

    public SavingsAccount applyKycResult(String accountId, boolean approved, String reason) {
        return withLock(accountId, account -> {
            if (account.getStatus() != SavingsAccount.Status.PENDING_KYC) {
                throw DomainException.rule("KYC_NOT_PENDING",
                        "KYC result only applies to PENDING_KYC accounts (status: " + account.getStatus() + ")");
            }
            account.setStatus(approved ? SavingsAccount.Status.ACTIVE : SavingsAccount.Status.REJECTED);
            account.setUpdatedAt(clock.instant());
            repository.save(account);
            events.publish(DomainEvent.of(TOPIC_KYC,
                    approved ? "kyc.assessment.approved" : "kyc.assessment.rejected",
                    Map.of("accountId", accountId, "reason", reason == null ? "" : reason)));
            return account;
        });
    }

    public SavingsAccount control(String accountId, String action) {
        return withLock(accountId, account -> {
            if (account.isTerminal()) {
                throw DomainException.rule("TERMINAL", "account is " + account.getStatus());
            }
            SavingsAccount.Status next;
            switch (action == null ? "" : action.toLowerCase()) {
                case "block" -> {
                    requireStatus(account, SavingsAccount.Status.ACTIVE, "block");
                    next = SavingsAccount.Status.BLOCKED;
                }
                case "unblock" -> {
                    requireStatus(account, SavingsAccount.Status.BLOCKED, "unblock");
                    next = SavingsAccount.Status.ACTIVE;
                }
                case "terminate" -> {
                    if (account.getBalanceMinor() != 0 || account.getAccruedInterestMinor() != 0) {
                        throw DomainException.rule("BALANCE_NOT_ZERO",
                                "close requires balance 0 and accrued interest 0 (capitalize first); "
                                        + "balance=" + account.getBalanceMinor()
                                        + " accrued=" + account.getAccruedInterestMinor());
                    }
                    next = SavingsAccount.Status.CLOSED;
                }
                default -> throw DomainException.invalid("UNKNOWN_ACTION",
                        "action must be block | unblock | terminate");
            }
            account.setStatus(next);
            account.setUpdatedAt(clock.instant());
            repository.save(account);
            events.publish(DomainEvent.of(TOPIC_ACCOUNTS, "account." + next.name().toLowerCase(),
                    Map.of("accountId", accountId)));
            return account;
        });
    }

    // ── postings ─────────────────────────────────────────────────────────────

    public AccountTransaction deposit(String accountId, long amountMinor, String reference) {
        return post(accountId, AccountTransaction.Type.DEPOSIT, amountMinor, reference, true);
    }

    public AccountTransaction withdraw(String accountId, long amountMinor, String reference) {
        return post(accountId, AccountTransaction.Type.WITHDRAWAL, amountMinor, reference, false);
    }

    public AccountTransaction chequeCredit(String accountId, long amountMinor, String chequeRef) {
        return post(accountId, AccountTransaction.Type.CHEQUE_CREDIT, amountMinor, chequeRef, true);
    }

    private AccountTransaction post(String accountId, AccountTransaction.Type type,
                                    long amountMinor, String reference, boolean credit) {
        if (amountMinor <= 0) {
            throw DomainException.invalid("AMOUNT_NOT_POSITIVE", "amountMinor must be > 0");
        }
        return withLock(accountId, account -> {
            switch (account.getStatus()) {
                case PENDING_KYC -> throw DomainException.rule("KYC_PENDING",
                        "no transactions until KYC approves this account");
                case REJECTED, CLOSED -> throw DomainException.rule("TERMINAL",
                        "account is " + account.getStatus());
                case BLOCKED -> {
                    if (!credit) {
                        throw DomainException.rule("ACCOUNT_BLOCKED", "debits are rejected on a blocked account");
                    }
                }
                case ACTIVE -> { }
            }
            if (!credit) {
                YearMonth month = YearMonth.from(clock.instant().atZone(ZoneOffset.UTC));
                int used = repository.countWithdrawalsIn(accountId, month);
                if (used >= monthlyWithdrawalCap) {
                    throw DomainException.rule("WITHDRAWAL_CAP_REACHED",
                            "monthly withdrawal cap (" + monthlyWithdrawalCap + ") reached for " + month);
                }
                long newBalance = account.getBalanceMinor() - amountMinor;
                if (newBalance < account.getMinBalanceMinor()) {
                    throw DomainException.rule("MIN_BALANCE_BREACH",
                            "withdrawal would leave " + newBalance + " minor units; minimum is "
                                    + account.getMinBalanceMinor() + " (no overdraft on savings)");
                }
            }
            long signed = credit ? amountMinor : -amountMinor;
            long newBalance = account.getBalanceMinor() + signed;
            Instant now = clock.instant();
            account.setBalanceMinor(newBalance);
            account.setUpdatedAt(now);
            repository.save(account);

            AccountTransaction tx = new AccountTransaction("TX-" + UUID.randomUUID(),
                    accountId, type, signed, newBalance, reference, now);
            repository.saveTransaction(tx);
            events.publish(DomainEvent.of(TOPIC_ACCOUNTS, "transaction.posted", Map.of(
                    "transactionId", tx.transactionId(), "accountId", accountId,
                    "type", type.name(), "amountMinor", signed,
                    "balanceAfterMinor", newBalance, "currency", account.getCurrency())));
            return tx;
        });
    }

    // ── interest (BIAN "Interest" behavior qualifier) ────────────────────────

    /** Accrue one day of simple interest. Idempotence is the scheduler's job (Phase 2b). */
    public SavingsAccount accrueDailyInterest(String accountId) {
        return withLock(accountId, account -> {
            if (account.getStatus() != SavingsAccount.Status.ACTIVE) {
                throw DomainException.rule("NOT_ACTIVE", "interest accrues on ACTIVE accounts only");
            }
            if (account.getBalanceMinor() > 0 && account.getInterestRateBp() > 0) {
                long daily = account.getBalanceMinor() * account.getInterestRateBp() / 10_000 / 365;
                account.setAccruedInterestMinor(account.getAccruedInterestMinor() + daily);
                account.setUpdatedAt(clock.instant());
                repository.save(account);
                events.publish(DomainEvent.of(TOPIC_ACCOUNTS, "interest.accrued", Map.of(
                        "accountId", accountId, "accruedMinor", daily,
                        "totalAccruedMinor", account.getAccruedInterestMinor())));
            }
            return account;
        });
    }

    /** Move accrued interest into the balance as an INTEREST posting. */
    public AccountTransaction capitalizeInterest(String accountId) {
        return withLock(accountId, account -> {
            if (account.getStatus() != SavingsAccount.Status.ACTIVE) {
                throw DomainException.rule("NOT_ACTIVE", "capitalization requires an ACTIVE account");
            }
            long accrued = account.getAccruedInterestMinor();
            if (accrued <= 0) {
                throw DomainException.rule("NOTHING_ACCRUED", "no accrued interest to capitalize");
            }
            Instant now = clock.instant();
            long newBalance = account.getBalanceMinor() + accrued;
            account.setBalanceMinor(newBalance);
            account.setAccruedInterestMinor(0);
            account.setUpdatedAt(now);
            repository.save(account);

            AccountTransaction tx = new AccountTransaction("TX-" + UUID.randomUUID(),
                    accountId, AccountTransaction.Type.INTEREST, accrued, newBalance,
                    "interest capitalization", now);
            repository.saveTransaction(tx);
            events.publish(DomainEvent.of(TOPIC_ACCOUNTS, "interest.capitalized", Map.of(
                    "accountId", accountId, "amountMinor", accrued, "balanceAfterMinor", newBalance)));
            return tx;
        });
    }

    // ── queries ──────────────────────────────────────────────────────────────

    public SavingsAccount retrieve(String accountId) {
        return repository.findById(accountId)
                .orElseThrow(() -> DomainException.notFound("ACCOUNT_UNKNOWN", "no account " + accountId));
    }

    public Collection<SavingsAccount> list() {
        return repository.findAll();
    }

    public List<AccountTransaction> transactions(String accountId) {
        retrieve(accountId);
        return repository.findTransactions(accountId);
    }

    public int withdrawalsUsedThisMonth(String accountId) {
        retrieve(accountId);
        return repository.countWithdrawalsIn(accountId,
                YearMonth.from(clock.instant().atZone(ZoneOffset.UTC)));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void requireStatus(SavingsAccount account, SavingsAccount.Status expected, String action) {
        if (account.getStatus() != expected) {
            throw DomainException.rule("WRONG_STATUS",
                    action + " requires " + expected + " (status: " + account.getStatus() + ")");
        }
    }

    private <T> T withLock(String accountId, java.util.function.Function<SavingsAccount, T> body) {
        ReentrantLock lock = locks.computeIfAbsent(accountId, k -> new ReentrantLock());
        lock.lock();
        try {
            return body.apply(retrieve(accountId));
        } finally {
            lock.unlock();
        }
    }
}
