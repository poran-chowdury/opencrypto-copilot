# 18. Authentication with an Identity Provider (OIDC / RS256 JWT)

In Steps 1 through 4, caller identity was passed in an unverified request header (`X-Demo-User`). While convenient for local prototyping, relying on client-supplied headers creates a critical security hole: any untrusted client can spoof any user's identity, drain their portfolio, or view private ledger balances.

In **Step 5 of the Agentic Roadmap** (`docs/ai/agentic-system.md`), we introduced cryptographic identity verification:
1. **`identity-service`** acts as an OpenID Connect (OIDC) provider, publishing public JSON Web Key Sets (JWKS) and signing RS256 JWT tokens with user tier claims (`STANDARD`, `PREMIUM`, `PRIVILEGED`).
2. **`api-gateway`** intercepts every ingress request, verifies JWT signatures and expiration, completely strips any incoming client identity headers to prevent spoofing, and injects gateway-verified headers (`X-Auth-User`, `X-Auth-Tier`, `X-Demo-User`) downstream.
3. **Downstream Services & MCP Tools** consume the verified identity, enforcing user isolation across portfolio, swaps, transactions, and AI agent tool invocations.

---

## 1. The Problem

### The Header-Spoofing Vulnerability
Before Step 5, any HTTP client could send:
```http
GET /api/v1/portfolio HTTP/1.1
Host: localhost:8080
X-Demo-User: alice
```
If Mallory wants to view or trade against Alice's balances, Mallory simply sends `X-Demo-User: alice`. The gateway innocently forwarded the header, and the downstream `wallet-service` returned Alice's portfolio.

Furthermore:
- Rate limiting keys could be spoofed to exhaust someone else's request budget.
- The AI copilot accepted `X-Demo-User` directly, allowing an attacker to instruct the copilot: *"What is my balance?"* and see another user's funds.
- There was no cryptographic proof of caller identity or standard discovery endpoints (`.well-known/openid-configuration`, `.well-known/jwks.json`).

---

## 2. Options

| Approach | Pros | Cons | Decision |
| :--- | :--- | :--- | :--- |
| **A. Shared Secret Symmetric JWT (HS256)** | Simple single secret string in config. | Every service that verifies tokens must know the shared secret. If any service leaks the secret, attackers can forge valid tokens for any user. | ❌ Rejected for microservices. |
| **B. Heavyweight External Keycloak Docker Container** | Full enterprise SSO UI, user administration, LDAP/Social login. | High memory footprint (~1.5 GB RAM), slow boot time (>45s), heavy operational friction for local development and CI tests. | ❌ Deferred for local dev; native provider preferred. |
| **C. Lightweight Embedded OIDC / JWKS Provider (RS256)** | Fast boot (<1.5s), zero external runtime dependencies, RFC 7519 / RFC 7517 compliant, exposes standard `.well-known/jwks.json` and `.well-known/openid-configuration`. Uses asymmetric RSA-2048 keys. | Must implement token issuance and public JWKS endpoint in Java. | **Selected.** Provides complete OIDC compatibility with sub-second tests and low resource footprint. |

---

## 3. What We Did

```
                         [Untrusted Client / Attacker]
                                     |
                Authorization: Bearer <token>
                X-Demo-User: bob  <-- MALICIOUS SPOOF
                                     v
                       +---------------------------+
                       |        api-gateway        |
                       |          (:8080)          |
                       +---------------------------+
                                     |
               1. Validate JWT signature against JWKS
               2. Check expiration & claims (sub=alice, tier=PREMIUM)
               3. STRIP incoming X-Demo-User & X-Auth-User
               4. INJECT verified headers:
                  - X-Auth-User: alice
                  - X-Auth-Tier: PREMIUM
                  - X-Demo-User: alice (legacy compat)
                                     |
        +----------------------------+----------------------------+
        |                            |                            |
        v                            v                            v
+----------------+           +----------------+           +-------------------+
| wallet-service |           |  swap-service  |           | ai-copilot-service|
|    (:8083)     |           |    (:8085)     |           |      (:8086)      |
+----------------+           +----------------+           +-------------------+
        ^                            ^                            |
        |                            |                            |
        +---- MCP Streamable HTTP ---+                            |
              (Propagates verified X-Auth-User & X-Auth-Tier) <---+
```

### 1. `identity-service` (:8081)
- **`RsaKeyProvider.java`**: Generates and maintains a 2048-bit RSA keypair, exposing public `JWKSet`.
- **`UserRepository.java`**: Pre-configures users with credentials and subscription tiers:
  - `user-1`: tier `PRIVILEGED`, roles `[ROLE_USER, ROLE_TRADER]`
  - `alice`: tier `PREMIUM`, roles `[ROLE_USER]`
  - `bob`: tier `STANDARD`, roles `[ROLE_USER]`
  - `demo`: tier `STANDARD`, roles `[ROLE_USER]`
- **`TokenService.java`**: Signs RS256 JWTs containing `iss`, `sub`, `tier`, `roles`, and `exp`.
- **`AuthController.java`**: Exposes:
  - `GET /api/v1/auth/.well-known/jwks.json` (Public JWKS key set)
  - `GET /api/v1/auth/.well-known/openid-configuration` (OIDC Discovery)
  - `POST /api/v1/auth/login` (and `/token`) -> Authenticates credentials, returns JWT
  - `GET /api/v1/auth/me` -> Inspect current caller claims
  - `GET /api/v1/auth/users` -> Directory of pre-configured test accounts

### 2. `api-gateway` (:8080)
- **`JwtTokenValidator.java`**: Non-blocking, reactive JWT validation using Nimbus JOSE + Spring WebFlux `WebClient`. Automatically caches the `JWKSet` in memory and refreshes reactively upon key rotation.
- **`JwtAuthFilter.java`**: Global filter (`Ordered.HIGHEST_PRECEDENCE + 5`):
  - **Public Routes:** `/api/v1/auth/**`, `/api/v1/prices/**`, `/api/v1/assets/**`, `/api/v1/market/**`, `/api/v1/system/**`, `/actuator/**`. Passes through while removing any client-injected `X-Auth-*` headers.
  - **Protected Routes:** `/api/v1/wallet/**`, `/api/v1/portfolio/**`, `/api/v1/swaps/**`, `/api/v1/transactions/**`, `/api/v1/ai/**`. Requires `Authorization: Bearer <token>`.
  - **Anti-Spoofing:** Strips all client-supplied `X-Demo-User`, `X-Auth-User`, `X-Auth-Tier` headers and injects verified values from the token claims.
  - **RFC 7807 Problem JSON:** Missing or invalid tokens return standard HTTP 401 Unauthorized problem responses.
- **`RateLimitFilter.java`**: Updated `clientKey()` to prioritize verified `X-Auth-User` over IP addresses or legacy headers.

### 3. Downstream Services & MCP Token Propagation
- `AiController.java` maps caller identity from gateway-verified `X-Auth-User` and `X-Auth-Tier`.
- `McpCallerContext.java` automatically propagates `X-Auth-User` and `X-Auth-Tier` headers across outbound MCP streamable HTTP requests to domain tool servers.

---

## 4. See It Yourself

### A. Inspect Public JWKS and OIDC Discovery
```powershell
# Public JWKS key set
Invoke-RestMethod -Uri "http://localhost:8080/api/v1/auth/.well-known/jwks.json"

# OIDC Discovery document
Invoke-RestMethod -Uri "http://localhost:8080/api/v1/auth/.well-known/openid-configuration"
```

### B. Verify 401 Rejection on Unauthenticated Request
```powershell
# Attempting to access protected portfolio without a token returns HTTP 401
try {
  Invoke-RestMethod -Uri "http://localhost:8080/api/v1/portfolio"
} catch {
  $_.Exception.Response.StatusCode # Returns 401 Unauthorized
}
```

### C. Authenticate and Access Protected Endpoints
```powershell
# 1. Login as Alice
$login = @{ username = "alice"; password = "password" } | ConvertTo-Json
$auth = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/auth/login" -Method Post -Body $login -ContentType "application/json"
$token = $auth.token

# 2. View Alice's portfolio with Bearer token
Invoke-RestMethod -Uri "http://localhost:8080/api/v1/portfolio" -Headers @{ Authorization = "Bearer $token" }
```

### D. Verify Ingress Header Spoofing is Blocked
```powershell
# Attacker supplies Alice's token, but attempts to spoof Bob in the headers
$spoofHeaders = @{
  Authorization = "Bearer $token"
  "X-Demo-User" = "bob"
  "X-Auth-User" = "bob"
}

$resp = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/portfolio" -Headers $spoofHeaders
# Must return Alice's portfolio, NOT Bob's!
Write-Host "Returned user: $($resp.userId)" # Outputs: alice
```

### E. AI Copilot Interaction Under Verified Identity
```powershell
# Copilot grounds balance strictly for the authenticated token subject
$chatBody = @{ message = "What is my balance?" } | ConvertTo-Json
Invoke-RestMethod -Uri "http://localhost:8080/api/v1/ai/chat" -Method Post -Body $chatBody -ContentType "application/json" -Headers @{ Authorization = "Bearer $token" }
```

---

## 5. Pitfalls & Lessons

1. **Blocking Network Calls on Netty Event Loops:**
   - *Problem:* Default Nimbus `RemoteJWKSet` uses blocking `HttpURLConnection` on key lookup. Running this inside a Spring Cloud Gateway `GlobalFilter` blocks Netty reactor threads, causing thread starvation and latency spikes.
   - *Solution:* Implemented `JwtTokenValidator` with reactive non-blocking `WebClient` and an in-memory `AtomicReference<JWKSet>` cache. Key resolution and signature verification execute purely in-memory in under 0.1 ms.
2. **Filter Ordering vs Rate Limiting:**
   - *Problem:* If `RateLimitFilter` (`HIGHEST_PRECEDENCE + 10`) executes before `JwtAuthFilter`, rate limits are keyed by unverified client headers or fallback IP addresses.
   - *Solution:* `JwtAuthFilter` executes at `HIGHEST_PRECEDENCE + 5`. It authenticates the caller and sets `X-Auth-User` first; the rate limiter then keys off the verified subject.
3. **Clock Skew on Token Expiration:**
   - *Problem:* Minor clock differences between services can cause freshly issued tokens to be rejected as expired.
   - *Solution:* Token lifetimes are given reasonable validity (3600s), and `Instant.now()` comparisons allow standard leeway when evaluating `exp` and `nbf`.
4. **Test Isolation Without External Dependencies:**
   - *Problem:* Gateway unit tests shouldn't require a live `identity-service` running over HTTP.
   - *Solution:* `JwtTokenValidator.setCachedJwks()` allows unit tests (`JwtAuthFilterTest`) to inject ephemeral in-memory RSA keys, testing valid tokens, expired tokens, and spoofing attempts in milliseconds with zero network I/O.

---

## 6. Interview Questions

1. **Why is RS256 preferred over HS256 in a microservice architecture?**
   - *Answer:* HS256 is symmetric: every service that validates the token must possess the secret key. If any downstream service is compromised, an attacker can sign arbitrary tokens as any user. RS256 is asymmetric: the identity provider signs tokens with a private key, while gateways and resource servers verify tokens using a freely distributable public key (via JWKS).
2. **How does an API Gateway prevent Identity Spoofing?**
   - *Answer:* The gateway acts as the trust boundary. It strips all incoming client-supplied identity headers (`X-User-Id`, `X-Auth-User`, `X-Demo-User`) at ingress, validates the cryptographic signature and expiration of the `Authorization: Bearer <token>`, and injects trusted, verified identity headers downstream.
3. **How do you handle JWKS key rotation without downtime?**
   - *Answer:* The identity provider publishes multiple keys in its JWKS (`.well-known/jwks.json`), each identified by a unique `kid` (Key ID). When signing, tokens include the `kid` in the JOSE header. Consumers cache the JWKS in memory. If a token arrives with an unknown `kid`, the consumer refreshes the JWKS from the endpoint to acquire the new public key before falling back to rejection.
4. **Why shouldn't downstream microservices parse and validate JWTs directly if the gateway already did?**
   - *Answer:* In a Zero-Trust architecture, the gateway offloads edge TLS and coarse authentication, injecting verified headers (`X-Auth-User`, `X-Auth-Tier`). For internal service-to-service calls (or when defense-in-depth is required), services can also inspect signed JWTs or use mTLS. However, stripping untrusted ingress headers at the gateway prevents external attackers from exploiting unauthenticated internal endpoints.
5. **How does user authentication interact with Model Context Protocol (MCP) tool execution?**
   - *Answer:* AI models generate tool invocation intent but must never provide caller identity arguments (preventing prompt injection spoofing). Instead, the host application context maintains the authenticated caller in a `ThreadLocal` or reactive context and injects verified authentication headers (`X-Auth-User`, `X-Auth-Tier`) into the outbound MCP HTTP transport request, ensuring tools operate under the caller's actual identity.

