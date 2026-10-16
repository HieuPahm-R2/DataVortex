"""Validated SQLite snapshot publisher for tests and opt-in development analytics.

This model proves replace-scope/decimal rules, not distributed leases or cloud recovery.
"""
import hashlib
import json
import sqlite3
from decimal import Decimal, localcontext
from pathlib import Path
from pipeline.normalize import QualityError, normalized_rows


class ReferenceWarehouse:
    def __init__(self, path=":memory:"):
        self.db = sqlite3.connect(str(path))
        self.db.executescript("""
            CREATE TABLE IF NOT EXISTS facts (
              tenant TEXT, platform TEXT, account TEXT, campaign TEXT, report_date TEXT,
              currency TEXT, payload TEXT NOT NULL,
              PRIMARY KEY(tenant,platform,account,campaign,report_date,currency));
            CREATE TABLE IF NOT EXISTS publications (
              tenant TEXT, platform TEXT, account TEXT, sequence INTEGER, run_id TEXT, data_as_of TEXT,
              PRIMARY KEY(tenant,platform,account));
            CREATE TABLE IF NOT EXISTS runs (run_id TEXT PRIMARY KEY, manifest_hash TEXT, rows INTEGER);
            CREATE TEMP TABLE stage (
              tenant TEXT, platform TEXT, account TEXT, campaign TEXT, report_date TEXT,
              currency TEXT, payload TEXT NOT NULL,
              PRIMARY KEY(tenant,platform,account,campaign,report_date,currency));
        """)

    def close(self):
        self.db.close()

    def publish(self, manifest_path, registry_path):
        manifest_bytes = Path(manifest_path).read_bytes()
        manifest = json.loads(manifest_bytes)
        digest = hashlib.sha256(manifest_bytes).hexdigest()
        received = 0
        with self.db:
            # Serialize independent local publishers before reading the current run sequence.
            self.db.execute("BEGIN IMMEDIATE")
            self.db.execute("DELETE FROM stage")
            for row in normalized_rows(manifest_path, registry_path):
                received += 1
                key = (row["tenant_id"], row["platform"], row["account_id"], row["campaign_id"],
                       row["report_date"], row["currency"])
                payload = json.dumps(row, sort_keys=True)
                existing = self.db.execute("SELECT payload FROM stage WHERE tenant=? AND platform=? "
                    "AND account=? AND campaign=? AND report_date=? AND currency=?", key).fetchone()
                if existing and existing[0] != payload:
                    raise QualityError("CONFLICTING_DUPLICATE_GRAIN: no arbitrary winner")
                self.db.execute("INSERT OR IGNORE INTO stage VALUES (?,?,?,?,?,?,?)", (*key, payload))
            if received != manifest["row_count"]:
                raise QualityError("ROW_COUNT_MISMATCH")
            prior = self.db.execute("SELECT manifest_hash, rows FROM runs WHERE run_id=?",
                                    (manifest["run_id"],)).fetchone()
            if prior:
                if prior[0] != digest:
                    raise QualityError("IMMUTABLE_RUN_CONFLICT")
                return {"status": "REPLAY", "rows": prior[1]}
            scope = (manifest["tenant_id"], manifest["platform"], manifest["account_id"])
            current = self.db.execute("SELECT sequence FROM publications WHERE tenant=? AND platform=? AND account=?",
                                      scope).fetchone()
            if current and current[0] >= manifest["run_sequence"]:
                raise QualityError("STALE_RUN: reconcile newer publication before retry")
            self.db.execute("DELETE FROM facts WHERE tenant=? AND platform=? AND account=? AND report_date BETWEEN ? AND ?",
                            (*scope, manifest["date_from"], manifest["date_to"]))
            self.db.execute("INSERT INTO facts SELECT * FROM stage")
            self.db.execute("INSERT OR REPLACE INTO publications VALUES (?,?,?,?,?,?)",
                            (*scope, manifest["run_sequence"], manifest["run_id"], manifest["fetched_at"]))
            count = self.db.execute("SELECT COUNT(*) FROM stage").fetchone()[0]
            self.db.execute("INSERT INTO runs VALUES (?,?,?)", (manifest["run_id"], digest, count))
        return {"status": "PUBLISHED", "rows": count}

    def summary(self, tenant_id, currency):
        if not currency:
            raise QualityError("CURRENCY_REQUIRED: never add different currencies")
        metrics = {key: Decimal(0) for key in ("impressions", "clicks", "conversions", "cost", "conversion_value")}
        with localcontext() as context:
            context.prec = 38
            for (payload,) in self.db.execute("SELECT payload FROM facts WHERE tenant=? AND currency=?", (tenant_id, currency)):
                row = json.loads(payload)
                for key in metrics:
                    metrics[key] += Decimal(row[key])
            for name, numerator, denominator in (("ctr", "clicks", "impressions"), ("cpc", "cost", "clicks"),
                    ("cpa", "cost", "conversions"), ("roas", "conversion_value", "cost"),
                    ("cvr", "conversions", "clicks")):
                metrics[name] = metrics[numerator] / metrics[denominator] if metrics[denominator] else None
        return {"currency": currency, **{key: format(value, "f") if value is not None else None
                                        for key, value in metrics.items()}}
