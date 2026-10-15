# AdInsight repository map

- [Product spec](../spec-v1.md): accepted behavior, invariants and MVP order.
- [Setup and validation](../README.md): executable consumer commands and their limits.
- [Harness workflow](WORKFLOW.md): repository authority, evidence and recovery.
- [Architecture](architecture.md): implemented boundaries, concrete service/entity/DTO/controller layout and missing modules.
- [OpenAPI](openapi.yaml): implemented HTTP contract.
- [Active implementation plan](plans/active/adinsight-v1.md): progress and next dependencies.
- [Grain/snapshot decision](decisions/0001-grain-and-snapshot-publication.md).

## Code and proof

- `backend/src/main/java/io/datavortex/adinsight/`: DDD contexts, use cases and adapters.
- `backend/src/main/resources/db/migration/`: MySQL schema, tenant FKs and queue leases.
- `backend/src/test/java/`: architecture, authorization, HTTP and MySQL concurrency tests.
- `pipeline/`, `tests/test_pipeline.py`: raw, Parquet and local snapshot oracle.
- `data/fixtures/small.json`: frozen fixture configuration.
- `warehouse/bigquery/`: explicit DDL, guarded publication transaction and summary query.
- `scripts/validate.ps1`: local package checks; `-Integration` adds MySQL.
- `scripts/demo_pipeline.py`: fresh local replay/correction/partial/empty scenario.
- [Domain glossary](../CONTEXT.md): shared business vocabulary.

## Harness maintenance

Core 0.1.10 is installed in `.harness-core/`; use `scripts/bin/harness.exe`
for `status`, `doctor` and safe updates. Consumer files customize the upstream base.
The supplied nested `repository-harness/` checkout is preserved as a reference.
Harness skills remain explicit-only; installation does not invoke them.
