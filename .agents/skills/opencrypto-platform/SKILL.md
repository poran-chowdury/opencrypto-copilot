---
name: opencrypto-platform
description: >-
  Procedures for building, testing, starting, and operating the OpenCrypto platform microservices
  and frontend locally on Windows. Use this skill when compiling the project, managing the local
  Docker infrastructure, starting or stopping services via PowerShell, handling Java 21 environment
  settings, or inspecting microservice health and logs.
---

# OpenCrypto Platform Operations Guide

This skill guides you through building, testing, running, and troubleshooting the OpenCrypto
microservices ecosystem locally on Windows.

## 1. Environment Requirements & Setup

This repository requires **Java 21** and Maven 3.9+. On Windows machines where older JDKs
(such as JDK 8 or 17) reside in the system path:

```powershell
$env:JAVA_HOME = "C:\Users\lenovo\.jdks\openjdk-21.0.1"
$env:Path = "C:\Users\lenovo\.jdks\openjdk-21.0.1\bin;" + $env:Path
java -version    # Must report openjdk version 21.0.1
mvn -version     # Must report Java version: 21.0.1
```

## 2. Compiling and Testing

### Backend (Maven Multi-Module)
- Fast compilation without tests:
  ```powershell
  mvn -B -q -DskipTests package
  ```
- Run unit tests for a single service:
  ```powershell
  mvn -pl services/ai-copilot-service -am test "-Dsurefire.failIfNoSpecifiedTests=false"
  ```
- Run integration tests (requires Docker running):
  ```powershell
  mvn verify
  ```

### Frontend (React 19 + Vite)
```powershell
cd frontend
npm run dev      # Local dev server on http://localhost:5173
npm run build    # Type-check (tsc -b) & production bundle
npm run a11y     # Playwright + axe accessibility checks
```

## 3. Running the Stack Locally

The local developer environment runs Docker containers for infrastructure (Postgres on `:5433`,
Redis on `:6380`, RabbitMQ on `:5673`, observability tools) and microservices on the host:

```powershell
# Reproducible simulated market
$env:MARKET_PROVIDER = 'simulated'
$env:MARKET_SEED = '42'

# Start infrastructure + 7 host services
.\scripts\run-local.ps1

# Restart services without rebuilding JARs
.\scripts\run-local.ps1 -SkipBuild

# Stop services (and optionally containers)
.\scripts\stop-local.ps1
.\scripts\stop-local.ps1 -Infra
```

### Gateway Health Check
Verify all services are up through the gateway on `:8080`:
```powershell
foreach ($svc in 'asset','wallet','swap','transaction','ai','identity') {
  Invoke-RestMethod "http://localhost:8080/api/v1/system/$svc/health"
}
```

## 4. Port Reference & Architecture Boundaries

- **Gateway**: `8080` (the only public door)
- **Services**: identity `8081`, asset `8082`, wallet `8083`, transaction `8084`, swap `8085`, ai-copilot `8086`
- **Infra (non-default host ports)**: Postgres `5433`, Redis `6380`, RabbitMQ `5673` (management UI `15673`), Grafana `3000`
- **Rule**: Cross-service state changes go through RabbitMQ sagas (`opencrypto.events`); database schemas are strictly isolated by Postgres user roles.

