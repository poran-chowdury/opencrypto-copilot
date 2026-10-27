# ADR 0006: Distributed tracing with W3C Trace Context and OpenTelemetry
Status: Accepted (supersedes the home-made correlation id as the primary request identifier).

Context: a request crosses the gateway, several services and RabbitMQ (through a transactional outbox). Correlation ids grouped log lines but couldn't show timing or call structure, and a custom header is understood by no other tool.

Decision: Spring Boot's Micrometer Tracing with the OpenTelemetry bridge in every service; W3C `traceparent` propagation over HTTP and AMQP; spans exported over OTLP to Grafana Tempo. The outbox stores the writing transaction's `traceparent` and the publisher creates a PRODUCER span from it, so asynchronous steps stay in the original trace. The gateway uses the trace id as the user-visible correlation id. Logs carry trace/span ids, and Grafana links logs, traces and metrics (exemplars, service map).

Alternatives: keep correlation ids only (no timing or causality); vendor agents (lock-in); the OpenTelemetry Java agent (zero code, but harder to customise the outbox hop and less visible for learning).

Consequences: one more backend to run (Tempo); 100% sampling locally, which needs head or tail sampling in production; a schema change in every outbox table (applied as an idempotent repeatable migration).
