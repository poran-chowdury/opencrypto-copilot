# ADR 0005: Global rate limiting at the gateway with Redis token buckets
Status: Accepted.

Context: every public endpoint needs protection from abusive or buggy clients, consistently across gateway instances.

Decision: a gateway global filter applies Redis-backed token buckets (atomic Lua) keyed by user (or IP), with cost-based policies: read, write and ai. Rejections are RFC 7807 429s with Retry-After. The limiter fails open if Redis is unavailable.

Consequences: limits are configured in one place and services stay unaware of them. Per-endpoint quotas, if needed later, become extra policies in the same filter.
