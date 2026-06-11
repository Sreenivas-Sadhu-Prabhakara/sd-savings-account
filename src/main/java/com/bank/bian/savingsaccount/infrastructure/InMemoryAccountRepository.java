package com.bank.bian.savingsaccount.infrastructure;

import com.bank.bian.savingsaccount.domain.AccountTransaction;
import com.bank.bian.savingsaccount.domain.AccountRepository;
import com.bank.bian.savingsaccount.domain.SavingsAccount;
import org.springframework.stereotype.Repository;

import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Phase 2a adapter; atomicity is the service layer's per-account lock. */
@Repository
public class InMemoryAccountRepository implements AccountRepository {

    private final Map<String, SavingsAccount> accounts = new ConcurrentHashMap<>();
    private final Map<String, List<AccountTransaction>> transactions = new ConcurrentHashMap<>();

    @Override
    public void save(SavingsAccount account) {
        accounts.put(account.getAccountId(), account);
    }

    @Override
    public Optional<SavingsAccount> findById(String accountId) {
        return Optional.ofNullable(accounts.get(accountId));
    }

    @Override
    public Collection<SavingsAccount> findAll() {
        return accounts.values();
    }

    @Override
    public void saveTransaction(AccountTransaction tx) {
        transactions.computeIfAbsent(tx.accountId(), k -> new CopyOnWriteArrayList<>()).add(tx);
    }

    @Override
    public List<AccountTransaction> findTransactions(String accountId) {
        return List.copyOf(transactions.getOrDefault(accountId, List.of()));
    }

    @Override
    public int countWithdrawalsIn(String accountId, YearMonth month) {
        return (int) transactions.getOrDefault(accountId, List.of()).stream()
                .filter(tx -> tx.type() == AccountTransaction.Type.WITHDRAWAL)
                .filter(tx -> YearMonth.from(tx.postedAt().atZone(ZoneOffset.UTC)).equals(month))
                .count();
    }
}
