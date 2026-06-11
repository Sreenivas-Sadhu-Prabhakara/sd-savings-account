# Savings Account

BIAN Service Domain microservice — **Phase 2a DEEP build** (graduated from the golden template; see `.bian-graduated`).

| | |
|---|---|
| **Business Area** | Operations and Execution |
| **Business Domain** | Account Management |
| **Functional Pattern** | Fulfill |
| **Control Record** | Savings Account Facility Fulfillment Arrangement |
| **K8s Namespace** | `bian-operations` |

## Business rules implemented

- **No overdraft, ever** — withdrawals may not take the balance below `minBalanceMinor` (enforced in code *and* by a DB CHECK once hydrated).
- **Monthly withdrawal cap** — `bian.savings.monthly-withdrawal-cap` (default 6, the classic savings rule); the cap counts WITHDRAWAL postings per UTC calendar month. Deposits are never capped.
- **Interest** — simple daily accrual at `interestRateBp` (basis points p.a.), floor arithmetic: `balance × bp / 10 000 / 365` minor units/day. `accrue` builds up `accruedInterestMinor`; `capitalize` moves it into the balance as an INTEREST posting. (A scheduler drives daily accrual in Phase 2b; the endpoint is the mechanism.)
- **KYC gating** — same semantics as Current Account (`PENDING_KYC` → no transactions; auto-approve flag for Phase 2a).
- **Closing** — requires balance **and accrued interest** both zero: capitalize first.
- Money: `long` minor units; rates: basis points. No floats.

## API & contracts (owned by this repo)

- REST: [`api/openapi.yaml`](api/openapi.yaml) · Events: [`api/events.yaml`](api/events.yaml)
- Base: `/v1/savings-account-facility-fulfillment-arrangement`
- Payments BQ: `deposit` / `withdraw` / `cheque-credit` / history · Interest BQ: `accrue` / `capitalize` · `balance` shows accrued interest + withdrawals used this month

```bash
mvn spring-boot:run
CR=/v1/savings-account-facility-fulfillment-arrangement
ID=$(curl -s -X POST localhost:8080$CR/initiate -H 'content-type: application/json' \
     -d '{"customerReference":"C-1","interestRateBp":100}' | jq -r .accountId)
curl -s -X POST localhost:8080$CR/$ID/payments/deposit -H 'content-type: application/json' -d '{"amountMinor":365000}'
curl -s -X POST localhost:8080$CR/$ID/interest/accrue
curl -s -X POST localhost:8080$CR/$ID/interest/capitalize
```

## Persistence

In-memory port/adapter. **Postgres ready to hydrate, not wired**: [`db/schema.sql`](db/schema.sql) + `db/seed.sql`, provisioned by `bian-platform/platform-infra/postgres/hydrate.sh` on explicit go-ahead. The no-overdraft invariant is a `CHECK (balance_minor >= 0)` at the DB layer too.

## Tests

`mvn verify` — interest floor-arithmetic, monthly cap (incl. month rollover via injected Clock), min-balance floor, close-requires-capitalize, plus a boot/API journey.
