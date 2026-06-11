package com.bank.bian.savingsaccount.api;

import com.bank.bian.savingsaccount.domain.AccountTransaction;
import com.bank.bian.savingsaccount.domain.SavingsAccount;
import com.bank.bian.savingsaccount.domain.SavingsAccountService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * BIAN semantic API for "Savings Account" — Phase 2a, real domain.
 * Control record: Savings Account Facility Fulfillment Arrangement.
 * Behavior qualifiers: payments, interest.
 *
 * Contract: api/openapi.yaml (owned by this repo).
 */
@RestController
@RequestMapping("/v1")
public class ServiceDomainController {

    static final String CR = "savings-account-facility-fulfillment-arrangement";

    private final SavingsAccountService service;

    public ServiceDomainController(SavingsAccountService service) {
        this.service = service;
    }

    @GetMapping("/service-domain")
    public Map<String, String> serviceDomain() {
        return Map.of(
                "serviceDomain", "Savings Account",
                "businessArea", "Operations and Execution",
                "businessDomain", "Account Management",
                "functionalPattern", "Fulfill",
                "assetType", "Savings Account Facility",
                "controlRecord", "Savings Account Facility Fulfillment Arrangement",
                "version", "0.2.0",
                "phase", "2a-deep"
        );
    }

    // ── control record lifecycle ─────────────────────────────────────────────

    public record OpenRequest(String customerReference, String currency,
                              Integer interestRateBp, Long minBalanceMinor) {}

    @PostMapping("/" + CR + "/initiate")
    public ResponseEntity<SavingsAccount> initiate(@RequestBody OpenRequest req) {
        SavingsAccount account = service.open(
                req.customerReference(),
                req.currency() == null ? "INR" : req.currency(),
                req.interestRateBp() == null ? 350 : req.interestRateBp(),
                req.minBalanceMinor() == null ? 0L : req.minBalanceMinor());
        return ResponseEntity.status(HttpStatus.CREATED).body(account);
    }

    @GetMapping("/" + CR)
    public Collection<SavingsAccount> list() {
        return service.list();
    }

    @GetMapping("/" + CR + "/{accountId}/retrieve")
    public SavingsAccount retrieve(@PathVariable String accountId) {
        return service.retrieve(accountId);
    }

    @PutMapping("/" + CR + "/{accountId}/control")
    public SavingsAccount control(@PathVariable String accountId, @RequestBody Map<String, String> body) {
        return service.control(accountId, body.get("action"));
    }

    @PutMapping("/" + CR + "/{accountId}/kyc-result")
    public SavingsAccount kycResult(@PathVariable String accountId, @RequestBody Map<String, Object> body) {
        boolean approved = Boolean.TRUE.equals(body.get("approved"));
        return service.applyKycResult(accountId, approved, (String) body.get("reason"));
    }

    // ── Payments behavior qualifier ──────────────────────────────────────────

    public record PostingRequest(long amountMinor, String reference) {}

    @PostMapping("/" + CR + "/{accountId}/payments/deposit")
    public ResponseEntity<AccountTransaction> deposit(@PathVariable String accountId,
                                                      @RequestBody PostingRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.deposit(accountId, req.amountMinor(), req.reference()));
    }

    @PostMapping("/" + CR + "/{accountId}/payments/withdraw")
    public ResponseEntity<AccountTransaction> withdraw(@PathVariable String accountId,
                                                       @RequestBody PostingRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.withdraw(accountId, req.amountMinor(), req.reference()));
    }

    @PostMapping("/" + CR + "/{accountId}/payments/cheque-credit")
    public ResponseEntity<AccountTransaction> chequeCredit(@PathVariable String accountId,
                                                           @RequestBody PostingRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.chequeCredit(accountId, req.amountMinor(), req.reference()));
    }

    @GetMapping("/" + CR + "/{accountId}/payments")
    public List<AccountTransaction> transactions(@PathVariable String accountId) {
        return service.transactions(accountId);
    }

    // ── Interest behavior qualifier ──────────────────────────────────────────

    @PostMapping("/" + CR + "/{accountId}/interest/accrue")
    public SavingsAccount accrue(@PathVariable String accountId) {
        return service.accrueDailyInterest(accountId);
    }

    @PostMapping("/" + CR + "/{accountId}/interest/capitalize")
    public ResponseEntity<AccountTransaction> capitalize(@PathVariable String accountId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.capitalizeInterest(accountId));
    }

    @GetMapping("/" + CR + "/{accountId}/balance")
    public Map<String, Object> balance(@PathVariable String accountId) {
        SavingsAccount a = service.retrieve(accountId);
        return Map.of(
                "accountId", a.getAccountId(),
                "currency", a.getCurrency(),
                "balanceMinor", a.getBalanceMinor(),
                "accruedInterestMinor", a.getAccruedInterestMinor(),
                "interestRateBp", a.getInterestRateBp(),
                "minBalanceMinor", a.getMinBalanceMinor(),
                "withdrawalsUsedThisMonth", service.withdrawalsUsedThisMonth(accountId),
                "status", a.getStatus().name());
    }
}
