import hashlib
import json
import sqlite3
import tempfile
import unittest
from concurrent.futures import ThreadPoolExecutor
from threading import Barrier
from decimal import Decimal
from pathlib import Path
from pipeline.fixtures import generate
from pipeline.local_analytics import query
from pipeline.normalize import normalized_rows
from pipeline.reference import ReferenceWarehouse

CONFIG = Path(__file__).resolve().parents[1] / "data/fixtures/small.json"


class LocalAnalyticsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.manifests = generate(CONFIG, self.root / "fixture")
        self.metadata = self.root / "fixture/metadata.json"
        self.registry = json.loads(self.metadata.read_text(encoding="utf-8"))
        self.database = self.root / "warehouse.sqlite"
        self.warehouse = ReferenceWarehouse(self.database)
        for manifest in self.manifests:
            self.warehouse.publish(manifest, self.metadata)
        self.tenant = self.registry["tenants"][0]["id"]
        self.request = {"tenantId": self.tenant, "dateFrom": "2026-08-01", "dateTo": "2026-12-31",
                        "daily": False, "accounts": self.scope(self.tenant)}

    def scope(self, tenant):
        return [{"accountId": account["id"], "currency": account["currency"],
                 "campaignIds": [c["id"] for c in self.registry["campaigns"] if c["account_id"] == account["id"]]}
                for account in self.registry["accounts"] if account["tenant_id"] == tenant]

    def tearDown(self):
        self.warehouse.close()
        self.temporary.cleanup()

    def test_groups_currency_and_uses_exact_sums_then_ratios(self):
        result = query(self.database, self.request)
        self.assertEqual("LOCAL", result["source"])
        self.assertEqual(2, len(result["inputWatermark"]))
        self.assertEqual(["USD", "VND"], [g["currency"] for g in result["groups"]])
        for group in result["groups"]:
            expected = self.warehouse.summary(self.tenant, group["currency"])
            expected["conversionValue"] = expected.pop("conversion_value")
            for key, value in group["totals"].items():
                self.assertEqual(Decimal(expected[key]) if expected[key] is not None else None,
                                 Decimal(value) if value is not None else None)
            self.assertEqual([], group["days"])

    def test_dates_campaign_scope_and_daily_agree_without_filling_missing_dates(self):
        manifest = json.loads(self.manifests[0].read_text(encoding="utf-8"))
        rows = list(normalized_rows(self.manifests[0], self.metadata))
        campaign = rows[0]["campaign_id"]
        day = manifest["date_from"]
        request = {**self.request, "dateFrom": day, "dateTo": day, "daily": True,
                   "accounts": [{"accountId": manifest["account_id"], "currency": manifest["currency"],
                                 "campaignIds": [campaign]}]}
        group = query(self.database, request)["groups"][0]
        expected = next(row for row in rows if row["report_date"] == day and row["campaign_id"] == campaign)
        self.assertEqual(Decimal(expected["cost"]), Decimal(group["totals"]["cost"]))
        self.assertEqual([{"date": day, "metrics": group["totals"]}], group["days"])
        # No allowed campaign means zero totals and no fabricated daily records.
        request["accounts"][0]["campaignIds"] = []
        group = query(self.database, request)["groups"][0]
        self.assertEqual("0", group["totals"]["cost"])
        self.assertIsNone(group["totals"]["cpa"])
        self.assertEqual([], group["days"])
        # Another tenant's metadata cannot bypass the tenant predicate.
        request["accounts"] = self.scope(self.registry["tenants"][1]["id"])
        self.assertTrue(all(g["totals"]["cost"] == "0" for g in query(self.database, request)["groups"]))

    def test_read_only_missing_database_is_an_error_and_valid_read_does_not_mutate(self):
        missing = self.root / "missing.sqlite"
        with self.assertRaises(sqlite3.OperationalError):
            query(missing, self.request)
        self.assertFalse(missing.exists())
        before = hashlib.sha256(self.database.read_bytes()).hexdigest()
        query(self.database, self.request)
        self.assertEqual(before, hashlib.sha256(self.database.read_bytes()).hexdigest())
        with self.assertRaises(ValueError):
            query(self.database, {**self.request, "dateTo": "2028-01-01"})

    def test_correction_changes_watermark_without_double_counting_and_empty_has_zero_null(self):
        before = query(self.database, self.request)
        corrected = generate(CONFIG, self.root / "fixture", "correction")
        for manifest in corrected:
            self.warehouse.publish(manifest, self.metadata)
        after = query(self.database, self.request)
        self.assertNotEqual(before["inputWatermark"], after["inputWatermark"])
        self.assertEqual(before["groups"][0]["totals"]["cost"], after["groups"][0]["totals"]["cost"])
        self.assertNotEqual(before["groups"][0]["totals"]["conversions"],
                            after["groups"][0]["totals"]["conversions"])
        empty = generate(CONFIG, self.root / "fixture", "empty")
        for manifest in empty:
            self.warehouse.publish(manifest, self.metadata)
        for group in query(self.database, self.request)["groups"]:
            self.assertEqual("0", group["totals"]["cost"])
            self.assertIsNone(group["totals"]["roas"])

    def test_independent_publishers_serialize_and_replay_one_immutable_run(self):
        corrected = generate(CONFIG, self.root / "fixture", "correction")
        ready = Barrier(2)

        def publish():
            warehouse = ReferenceWarehouse(self.database)
            try:
                ready.wait(timeout=10)
                return warehouse.publish(corrected[0], self.metadata)["status"]
            finally:
                warehouse.close()

        with ThreadPoolExecutor(max_workers=2) as pool:
            first, second = pool.submit(publish), pool.submit(publish)
            self.assertEqual(["PUBLISHED", "REPLAY"], sorted([first.result(15), second.result(15)]))
        result = query(self.database, self.request)
        self.assertTrue(any(w["runSequence"] == 2 for w in result["inputWatermark"]))

