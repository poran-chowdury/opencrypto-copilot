# ADR 0004: Enforce ledger invariants in the database
Status: Accepted.

Context: money must never be created, destroyed or overdrawn, even by a bug or a manual SQL statement.

Decision: a double-entry journal. A deferred constraint trigger checks that each entry balances per asset at commit; CHECK constraints prevent negative user balances; a composite foreign key keeps postings in the account's asset; triggers make the journal, postings and audit log append-only. Application code also validates, and a scheduled reconciliation re-verifies.

Consequences: some logic lives in SQL (covered by integration tests); invariant violations fail loudly at commit instead of corrupting balances.
