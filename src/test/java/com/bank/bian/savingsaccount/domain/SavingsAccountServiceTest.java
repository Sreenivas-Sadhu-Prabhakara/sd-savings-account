package com.bank.bian.savingsaccount.domain;

import com.bank.bian.savingsaccount.events.DomainEvent;
import com.bank.bian.savingsaccount.events.EventPublisher;
import com.bank.bian.savingsaccount.infrastructure.InMemoryAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Savings-specific rules: interest math, monthly cap, min balance, no overdraft. */
class SavingsAccountServiceTest {

    static class RecordingPublisher implements EventPublisher {
        final List<DomainEvent> events = new ArrayList<>();
        @Override public void publish(DomainEvent event) { events.add(event); }
    }

    /** Mutable fixed clock so tests can cross month boundaries deterministically. */
    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-06-15T10:00:00Z");
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    RecordingPublisher events;
    MutableClock clock;
    SavingsAccountService service; // cap of 2 to keep tests terse

    @BeforeEach
    void setUp() {
        events = new RecordingPublisher();
        clock = new MutableClock();
        service = new SavingsAccountService(new InMemoryAccountRepository(), events, true, 2, clock);
    }

    @Nested
    class NoOverdraft {
        @Test
        void withdrawalBelowMinBalanceRejected() {
            SavingsAccount a = service.open("C-100", "INR", 350, 100_00); // min ₹100
            service.deposit(a.getAccountId(), 500_00, "seed");
            // leaves exactly the minimum: allowed
            service.withdraw(a.getAccountId(), 400_00, "to-the-floor");
            assertThat(service.retrieve(a.getAccountId()).getBalanceMinor()).isEqualTo(100_00);
            // one paisa below the minimum: rejected
            assertThatThrownBy(() -> service.withdraw(a.getAccountId(), 1, "below-floor"))
                    .isInstanceOf(DomainException.class)
                    .hasMessageContaining("no overdraft");
        }
    }

    @Nested
    class MonthlyCap {
        @Test
        void thirdWithdrawalInSameMonthRejectedThenResetsNextMonth() {
            SavingsAccount a = service.open("C-101", "INR", 0, 0);
            service.deposit(a.getAccountId(), 1_000_00, "seed");
            service.withdraw(a.getAccountId(), 10_00, "w1");
            service.withdraw(a.getAccountId(), 10_00, "w2");
            assertThatThrownBy(() -> service.withdraw(a.getAccountId(), 10_00, "w3"))
                    .hasMessageContaining("cap");
            // deposits are NOT capped
            service.deposit(a.getAccountId(), 10_00, "credit-fine");
            // next month the counter resets
            clock.now = Instant.parse("2026-07-01T08:00:00Z");
            service.withdraw(a.getAccountId(), 10_00, "new-month");
        }
    }

    @Nested
    class Interest {
        @Test
        void dailyAccrualUsesFloorArithmetic() {
            // balance 365_000, 100 bp => 365000 * 100 / 10000 / 365 = 10 minor/day exactly
            SavingsAccount a = service.open("C-102", "INR", 100, 0);
            service.deposit(a.getAccountId(), 365_000, "seed");
            service.accrueDailyInterest(a.getAccountId());
            assertThat(service.retrieve(a.getAccountId()).getAccruedInterestMinor()).isEqualTo(10);
            service.accrueDailyInterest(a.getAccountId());
            assertThat(service.retrieve(a.getAccountId()).getAccruedInterestMinor()).isEqualTo(20);
        }

        @Test
        void capitalizationMovesAccruedIntoBalanceAsInterestPosting() {
            SavingsAccount a = service.open("C-103", "INR", 100, 0);
            service.deposit(a.getAccountId(), 365_000, "seed");
            service.accrueDailyInterest(a.getAccountId());
            AccountTransaction tx = service.capitalizeInterest(a.getAccountId());
            assertThat(tx.type()).isEqualTo(AccountTransaction.Type.INTEREST);
            assertThat(tx.amountMinor()).isEqualTo(10);
            SavingsAccount after = service.retrieve(a.getAccountId());
            assertThat(after.getBalanceMinor()).isEqualTo(365_010);
            assertThat(after.getAccruedInterestMinor()).isZero();
        }

        @Test
        void capitalizingNothingIsARuleViolation() {
            SavingsAccount a = service.open("C-104", "INR", 100, 0);
            assertThatThrownBy(() -> service.capitalizeInterest(a.getAccountId()))
                    .hasMessageContaining("no accrued interest");
        }
    }

    @Nested
    class Closing {
        @Test
        void closeRequiresZeroBalanceAndZeroAccrued() {
            SavingsAccount a = service.open("C-105", "INR", 100, 0);
            service.deposit(a.getAccountId(), 365_000, "seed");
            service.accrueDailyInterest(a.getAccountId());
            service.withdraw(a.getAccountId(), 365_000, "empty-balance");
            // balance is 0 but accrued interest is not — close must be refused
            assertThatThrownBy(() -> service.control(a.getAccountId(), "terminate"))
                    .hasMessageContaining("capitalize");
        }
    }
}
