-- Sample data for local exploration after hydration. Idempotent.
INSERT INTO savings_account (account_id, customer_reference, currency, balance_minor,
                             accrued_interest_minor, interest_rate_bp, min_balance_minor,
                             status, opened_at, updated_at)
VALUES
    ('SA-SEED-0001', 'C-1001', 'INR', 365000, 20, 100, 0,      'ACTIVE', now(), now()),
    ('SA-SEED-0002', 'C-2001', 'INR', 500000, 0,  350, 100000, 'ACTIVE', now(), now())
ON CONFLICT (account_id) DO NOTHING;

INSERT INTO savings_transaction (transaction_id, account_id, type, amount_minor,
                                 balance_after_minor, reference, posted_at)
VALUES
    ('TX-SAV-SEED-0001', 'SA-SEED-0001', 'DEPOSIT',  365000, 365000, 'opening deposit', now() - interval '2 days'),
    ('TX-SAV-SEED-0002', 'SA-SEED-0002', 'DEPOSIT',  500000, 500000, 'opening deposit', now() - interval '5 days')
ON CONFLICT (transaction_id) DO NOTHING;
