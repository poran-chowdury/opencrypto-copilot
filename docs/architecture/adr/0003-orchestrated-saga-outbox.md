# ADR 0003: Orchestrated saga with transactional outbox for swaps
Status: Accepted.

Context: a swap changes swap-service and wallet-service state; there is no distributed transaction, and brokers deliver at least once.

Decision: swap-service orchestrates the swap as a state machine (CREATED → PENDING → COMPLETED/FAILED). Commands and events are written to an outbox table in the same transaction as the state change and published with confirms; consumers dedupe by message id (inbox), and every wallet step is idempotent by swap id. Timeouts ask the wallet to release; the wallet's reply decides the outcome.

Alternatives: 2PC/XA (operationally heavy, couples availability); choreography (the flow is harder to see); synchronous HTTP (ambiguous outcomes on timeout).

Consequences: swaps are asynchronous (HTTP 202 + polling); more tables and a poller per service; outcomes stay consistent across crashes and redeliveries.
