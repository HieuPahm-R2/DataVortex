# Backend foundation and Keycloak setup

The backend is a modular monolith, with framework-free domain/application
modules and Spring HTTP/persistence/security adapters. Its current schema and
feature coverage is recorded in [architecture](architecture.md). This foundation
does not implement the remaining budget/report/forecast/bidding modules.

## Default OIDC mode

The API validates Keycloak JWT access tokens using Spring Security's resource
server. Set these environment variables in the backend process:

| Variable | Meaning |
| --- | --- |
| `OIDC_ISSUER_URI` | Exact realm issuer, such as `http://localhost:8081/realms/adinsight` for local development |
| `OIDC_AUDIENCE` | API audience, `adinsight-api` in the supplied realm |
| `OIDC_JWK_SET_URI` | Optional trusted JWKS endpoint; issuer validation remains enabled |

Issuer and audience are required. Missing values fail startup with a pointer to
this runbook. With no explicit JWKS URI, startup discovers the realm's keys and
therefore requires Keycloak readiness. The optional JWKS URI avoids startup
discovery but does not remove runtime key availability requirements. The decoder
uses RS256 and validates signature, issuer, audience, expiry, not-before and
nonempty subject. Tokens without expiry are rejected. OIDC mode accepts credentials
only through `Authorization: Bearer`; cookies, query parameters and Basic cannot
authenticate a request. API health/info endpoints also require authentication.

The backend does not issue passwords/access/refresh tokens. The OIDC client
uses Keycloak's authorization-code flow with S256 PKCE, and uses Keycloak for
login, refresh, MFA and logout. The frontend/login client is not implemented in
this slice. ID tokens are not API access tokens: the supplied client mapper adds
the API audience only to access tokens.

## Local Keycloak

Keycloak 26.8.1 is pinned. Copy `.env.example` to `.env` and replace the local
database and Keycloak admin passwords. Start the local stack from one Compose file:

```powershell
rtk proxy docker compose up -d
```

MySQL and Keycloak share the `adinsight` bridge network. Compose scopes its
actual network name to the project. Containers can resolve the service names
`mysql` and `keycloak`; host processes continue using the published localhost
ports. The existing MySQL and Keycloak data volumes are retained.

The Keycloak service binds to loopback port 8081 by default and imports
`infra/keycloak/adinsight-realm.json`. Open `http://localhost:8081/admin` and use
the admin identity configured in `.env`. There are no bundled realm users,
passwords or client secrets. The separate Keycloak volume retains local data.
Startup import does not overwrite an existing realm; use the admin console to
apply reviewed client changes after the first import.

The realm contains the `adinsight-api` resource client and public
`adinsight-web` authorization-code client. Its exact local redirect URI is
`http://localhost:5173/auth/callback`; implicit/password grants are disabled and
S256 PKCE is required. Adapt the registered redirect to the real OIDC client
before integrating another frontend. This import and `start-dev` command are
local development assets. Production Keycloak needs its own persistent database,
HTTPS hostname and deployment configuration; that deployment is not part of this
change.

Wait until `/realms/adinsight/.well-known/openid-configuration` responds. Export
DB and OIDC environment variables to the backend process, then run:

```powershell
rtk proxy ./mvnw.cmd -pl backend spring-boot:run
```

Compose reads `.env`; Spring Boot does not automatically import that file. Keep
`OIDC_ISSUER_URI` identical to the token's `iss`, including hostname and path.
For a backend in another container, configure reachable issuer/JWKS addresses
explicitly; `localhost` is relative to the requesting process.

## Provision an identity and tenant access

1. Create/enable the user in the `adinsight` Keycloak realm using your admin
   account. Record its immutable user ID (`sub`) and the exact issuer.
2. Through your database administration tool, create an ACTIVE local `users`
   row with a UUID and a unique email. Omit `password_hash` for an OIDC-only user.
3. Insert `user_identities(issuer, subject, user_id)` for that exact pair.
4. Create an ACTIVE tenant and its `memberships(tenant_id,user_id,role)` row.
   Roles are `TENANT_ADMIN`, `ANALYST`, or `VIEWER`, per spec section 5.

These administration operations are explicit: there is no automatic email
matching, registration, membership grant, or Keycloak-admin-to-platform-admin
mapping. Token roles/tenant claims do not grant application permissions.
Requests recheck local identity, active user/tenant and membership from MySQL.

```http
GET /api/v1/accounts?page=0&size=20
Authorization: Bearer <Keycloak access token for adinsight-api>
X-Tenant-Id: <tenant UUID>
```

Removing a local identity link or disabling the local user rejects the next
request with 401. Removing membership or disabling the tenant returns 403.
Provider-side logout/disable does not immediately invalidate an already issued
offline JWT; revoke local access for immediate application denial, or wait for
token expiry. Introspection/back-channel logout are not implemented.

## Local Basic compatibility

To use existing locally provisioned BCrypt users, explicitly activate
`SPRING_PROFILES_ACTIVE=local`. This mode requires a non-NULL password hash and
the existing CSRF token/session cookie on mutations. `GET /api/v1/csrf` exists
only in this profile. OIDC mode creates no authentication/CSRF sessions and does
not require that endpoint. Never activate `local` on the OIDC deployment.

## Pagination, errors and operations

Accounts use zero-based `page`, default `size=20`, maximum 100, fixed account ID
ascending order. Invalid values, noninteger parameters and offsets greater than
2147483647 return 400. MySQL fetches `size+1` in the verified tenant, returning
`items`, `page`, `size`, `hasMore`, `requestId`; no unbounded list or COUNT query.
Offset pages are live reads and can shift under concurrent metadata changes.

Errors use `code`, `message`, `requestId`, `details`. Validation details name
invalid fields without echoing values. Explicit invalid input is distinguished
from programming/configuration argument failures, which remain 500. Ingestion
date ranges are bounded to 366 inclusive days, per spec section 11.
Framework 404/405/406/415 errors retain
their HTTP status and protocol headers. Unexpected failures return safe 500;
database outages return 503, including authentication/membership lookups.
Invalid credentials/tokens remain 401. Dependency validation failures use a
safe 503 response without an authentication challenge. Responses never expose
SQL, rejected input, raw exception messages, secrets or stack traces.

Application logs use JSON, with requestId and verified tenantId/userId context
for downstream request work. Dependency/internal error logs record stable codes
and exception type without dumping raw messages. Existing worker job/stage logs
and metrics coverage remain a separate observability backlog.

## Validation and recovery

```powershell
rtk proxy powershell -NoProfile -File scripts/validate.ps1 -Integration
```

The normal integration command now starts isolated MySQL 8.4.8 and Keycloak
26.8.1 containers. It exercises authorization-code/S256 PKCE login through the
real Keycloak HTTP interface, rejection of missing PKCE and ID tokens,
real discovery/JWKS and signed access tokens,
identity linking, current DB roles, membership/user/tenant revocation, stateless
mutation and pagination. Unit/MockMvc proof rejects wrong signatures, issuer,
audience, expired/not-yet-valid/missing claims and token-supplied roles. Basic/CSRF
compatibility and safe error behavior are also tested. Containers are cleaned
up by Testcontainers; existing application/database services are not stopped.

V5 adds external identity links and permits NULL password hashes without
rewriting previous migrations. Preserve users/memberships when changing an
issuer and explicitly review new identity links. Do not drop identity rows or
roll back a database to run a previous binary. Existing BCrypt users can still
use the explicit local profile; OIDC-only users cannot authenticate with Basic.

CI and optional hooks are not installed or changed. No branch-protection,
production Keycloak, live browser login, benchmark or cloud deployment proof is
claimed by local integration tests.

Implementation references: [Spring Security JWT resource server](https://docs.spring.io/spring-security/reference/6.5/servlet/oauth2/resource-server/jwt.html),
[Keycloak containers/import](https://www.keycloak.org/server/containers), and
[Keycloak 26.8.1 release](https://www.keycloak.org/2026/10/keycloak-2681-released).
