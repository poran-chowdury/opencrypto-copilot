# ADR 0002: Schema per service on one PostgreSQL
Status: Accepted.

Context: services must own their data, but running one Postgres per service is heavy for local development.

Decision: one PostgreSQL instance; one schema and one login role per service; each service runs its own Flyway migrations. Roles cannot use other schemas.

Consequences: isolation is enforced by the database, not by convention. Cross-service joins are impossible (by design); data needed elsewhere is fetched over HTTP or projected from events. Moving a schema to its own server later is a configuration change. The shared instance remains a single point of failure locally.
