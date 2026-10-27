# ADR 0001: Multi-module microservices monorepo
Status: Accepted for portfolio starter.

Decision: Maintain separate Spring Boot deployables in one Maven reactor.

Rationale: A single repository improves discoverability and consistent builds while keeping service boundaries explicit.

Consequences: Database ownership, service-to-service security and independent deployment need deliberate implementation. Avoid sharing domain classes that tightly couple services.
