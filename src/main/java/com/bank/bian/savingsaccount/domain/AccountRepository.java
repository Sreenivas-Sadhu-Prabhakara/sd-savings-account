package com.bank.bian.savingsaccount.domain;

import java.time.YearMonth;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Persistence port. In-memory adapter now; Postgres adapter when the platform
 * hydrates (db/schema.sql is ready — see bian-platform/platform-infra/postgres/).
 */
public interface AccountRepository {

    void save(SavingsAccount account);

    Optional<SavingsAccount> findById(String accountId);

    Collection<SavingsAccount> findAll();

    void saveTransaction(AccountTransaction tx);

    List<AccountTransaction> findTransactions(String accountId);

    /** Needed for the monthly-withdrawal-cap rule. */
    int countWithdrawalsIn(String accountId, YearMonth month);
}
