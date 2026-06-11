-- Savings Account service domain — Postgres schema.
-- READY TO HYDRATE, NOT YET WIRED (see platform-infra/postgres/hydrate.sh).
-- Money: BIGINT minor units. Rates: basis points.

CREATE TABLE IF NOT EXISTS savings_account (
    account_id             VARCHAR(40)  PRIMARY KEY,
    customer_reference     VARCHAR(64)  NOT NULL,
    currency               CHAR(3)      NOT NULL,
    balance_minor          BIGINT       NOT NULL DEFAULT 0 CHECK (balance_minor >= 0),  -- no overdraft, ever
    accrued_interest_minor BIGINT       NOT NULL DEFAULT 0 CHECK (accrued_interest_minor >= 0),
    interest_rate_bp       INTEGER      NOT NULL CHECK (interest_rate_bp BETWEEN 0 AND 2000),
    min_balance_minor      BIGINT       NOT NULL DEFAULT 0 CHECK (min_balance_minor >= 0),
    status                 VARCHAR(12)  NOT NULL
        CHECK (status IN ('PENDING_KYC','ACTIVE','BLOCKED','REJECTED','CLOSED')),
    opened_at              TIMESTAMPTZ  NOT NULL,
    updated_at             TIMESTAMPTZ  NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_sav_customer ON savings_account (customer_reference);
CREATE INDEX IF NOT EXISTS idx_sav_status   ON savings_account (status);

CREATE TABLE IF NOT EXISTS savings_transaction (
    transaction_id      VARCHAR(40)  PRIMARY KEY,
    account_id          VARCHAR(40)  NOT NULL REFERENCES savings_account (account_id),
    type                VARCHAR(16)  NOT NULL
        CHECK (type IN ('DEPOSIT','WITHDRAWAL','INTEREST','CHEQUE_CREDIT')),
    amount_minor        BIGINT       NOT NULL CHECK (amount_minor <> 0),
    balance_after_minor BIGINT       NOT NULL CHECK (balance_after_minor >= 0),
    reference           VARCHAR(140),
    posted_at           TIMESTAMPTZ  NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_sav_tx_account_posted ON savings_transaction (account_id, posted_at DESC);
-- supports the monthly-withdrawal-cap count
CREATE INDEX IF NOT EXISTS idx_sav_tx_withdrawals
    ON savings_transaction (account_id, posted_at) WHERE type = 'WITHDRAWAL';
