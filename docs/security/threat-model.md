# Initial threat model

Assets: identity, portfolio data, ledger integrity, swap requests, provider credentials, audit records.

Threats: IDOR, forged identity headers, duplicate/replayed swaps, prompt injection, unsafe AI tool arguments, leaked secrets, stale/manipulated prices, rounding errors, partial failures.

Mitigations to implement: validated JWT identity and service-level authorization; derive user ID from trusted security context; server-side validation of every amount/asset/quote; idempotency and atomic ledger writes; allowlisted AI tools with strict schemas and user confirmation; secrets manager and rotation; BigDecimal/NUMERIC with explicit scale; audit logs, rate limits, dependency scanning and backup/restore tests.

This is not a security certification. The scaffold is not production-ready.
