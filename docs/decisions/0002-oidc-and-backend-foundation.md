# OIDC identity and bounded account reads

Date: 2026-10-16. Status: accepted by repository owner in chat.

The owner chose Keycloak/OIDC, explicitly provisioned identity links, and a
foundation-first scope before the remaining schema modules. This extends spec
sections 5, 7 and 11; their role and tenant isolation requirements remain authoritative.

The backend is a JWT resource server. Keycloak owns login, credentials, MFA,
refresh tokens and browser logout. Required issuer and API audience are explicit
configuration. Verify signature, issuer, audience, expiry and not-before; reject
tokens without expiry or subject. Default operation accepts Bearer headers only.
The `local` profile explicitly enables the existing Basic/BCrypt mechanism and
retains session CSRF protection. OIDC does not authenticate through cookies and
does not create authentication or CSRF sessions.

`user_identities` links the exact, case-sensitive `(issuer, subject)` pair to an
existing `users.id`. No email matching or automatic user/membership provisioning
occurs. An unmapped or inactive user receives 401. Tenant membership, current user
and tenant status, and role come from MySQL on each request; JWT tenant/role claims
never bypass them. Local user/membership revocation takes effect on subsequent
requests. Provider-side logout or disabling a Keycloak user does not immediately
revoke an already issued offline-validated access token; it remains valid until
expiry unless local access is revoked. Token introspection is outside this slice.

OIDC-only users have NULL `password_hash`; Basic does not select them. V5 is an
additive identity migration and preserves existing password hashes. Linking users
is a privileged database administration operation, not a public API.

`GET /accounts` uses zero-based `page`, default `size=20`, maximum 100 and fixed
ascending account ID order. Validation rejects negative pages, invalid sizes and
offsets beyond JPA's integer range. MySQL fetches a slice of size+1, scoped to
the verified tenant, without an unbounded list or total count. Return `items`,
`page`, `size`, `hasMore`, and `requestId`. These are live offset pages; concurrent
inserts/deletes can shift pages. Search retains its separate PIT/cursor policy.

HTTP errors retain the existing code/message/requestId fields and add details,
with safe field names and generic validation messages. Framework statuses and
headers are preserved. Database authentication outages return 503 rather than
401; invalid credentials/tokens remain 401. Responses never expose rejected
values, raw exception messages, SQL, credentials or stack traces.

Proof belongs to the existing Maven/MockMvc/MySQL commands, including real local
Keycloak discovery/JWKS and signed-token tests. This decision does not authorize
production deployment, CI/merge policy changes or new rate-limit quotas.
