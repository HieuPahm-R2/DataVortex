# AdInsight

Advertising metadata and performance ingestion for multiple advertisers.

## Language

**Tenant**: An advertiser whose metadata and performance data share an authorization scope.

**Membership**: A user's role within one tenant. A user may hold memberships in several tenants.

**Ad account**: A source-platform advertising account owned by exactly one tenant.
_Avoid_: User account

**Ingestion job**: A durable request to fetch and publish one account/report/date scope.

**Snapshot**: All source rows for an account, report kind and inclusive date range at one fetch time.
_Avoid_: Delta

**Run sequence**: A monotonically increasing ingestion order for one tenant/account/report kind.

**Publication**: Replacement of the snapshot's fact scope and associated marts after quality gates succeed.

**Checkpoint**: The last successfully published ingestion position for one account and report kind.
