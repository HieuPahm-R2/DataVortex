"""Prepare a new, isolated development dataset and reviewable MySQL metadata SQL.

Creates files only. Import metadata.sql into a migrated EMPTY dev database, then
link your existing Keycloak user to one of the generated tenant UUIDs.
"""
import argparse
import json
from pathlib import Path
from pipeline.fixtures import generate, write_json
from pipeline.normalize import normalize_to_parquet
from pipeline.reference import ReferenceWarehouse


def sql_text(value):
    # Hex UTF-8 literals avoid SQL mode/backslash ambiguity and preserve Vietnamese text.
    return "CONVERT(X'" + str(value).encode("utf-8").hex() + "' USING utf8mb4)"


def prepare(output):
    output = Path(output).resolve()
    output.mkdir(parents=True, exist_ok=False)
    manifests = generate("data/fixtures/small.json", output / "fixtures")
    metadata = output / "fixtures/metadata.json"
    registry = json.loads(metadata.read_text(encoding="utf-8"))
    warehouse = ReferenceWarehouse(output / "warehouse.sqlite")
    try:
        for manifest in manifests:
            normalize_to_parquet(manifest, metadata, output / "normalized")
            warehouse.publish(manifest, metadata)
    finally:
        warehouse.close()
    statements = ["-- Development fixture only; intentionally fails on existing UUIDs.",
                  "-- No users, passwords, identity links or memberships are installed.",
                  "START TRANSACTION;"]
    def insert(table, columns, values):
        statements.append("INSERT INTO " + table + "(" + ",".join(columns) + ") VALUES (" +
                          ",".join(sql_text(v) for v in values) + ");")
    for tenant in registry["tenants"]:
        insert("tenants", ["id", "name", "status"], [tenant["id"], tenant["name"], "ACTIVE"])
    for account in registry["accounts"]:
        columns = ["id", "tenant_id", "platform", "source_account_id", "currency", "timezone"]
        insert("ad_accounts", columns + ["status"], [account[c] for c in columns] + ["ACTIVE"])
    for campaign in registry["campaigns"]:
        columns = ["id", "tenant_id", "account_id", "source_campaign_id", "name", "source_status"]
        insert("campaigns", columns + ["local_tags"], [campaign[c] for c in columns] + ["[]"])
    for group in registry["ad_groups"]:
        columns = ["id", "tenant_id", "campaign_id", "source_ad_group_id"]
        insert("ad_groups", columns + ["name", "status"], [group[c] for c in columns] + ["Demo group", "ENABLED"])
    for keyword in registry["keywords"]:
        columns = ["id", "tenant_id", "ad_group_id", "campaign_id", "source_keyword_id",
                   "text", "match_type", "status", "bid_micros"]
        insert("keywords", columns, [keyword[c] for c in columns])
    statements.append("COMMIT;")
    (output / "metadata.sql").write_text("\n".join(statements) + "\n", encoding="utf-8")
    evidence = {"source": "LOCAL", "database": str(output / "warehouse.sqlite"),
                "metadataSql": str(output / "metadata.sql"), "tenantIds": [t["id"] for t in registry["tenants"]],
                "campaignId": registry["campaigns"][0]["id"],
                "dateFrom": json.loads(manifests[0].read_text(encoding="utf-8"))["date_from"],
                "dateTo": json.loads(manifests[0].read_text(encoding="utf-8"))["date_to"]}
    write_json(output / "setup.json", evidence)
    return evidence


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, help="New directory; existing output is refused")
    print(json.dumps(prepare(parser.parse_args().output), ensure_ascii=False))

