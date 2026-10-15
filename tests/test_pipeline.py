import gzip
import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from pipeline.fixtures import generate
from pipeline.normalize import QualityError, normalize_to_parquet, normalized_rows
from pipeline.reference import ReferenceWarehouse

CONFIG = Path(__file__).resolve().parents[1] / "data/fixtures/small.json"


class SnapshotTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.manifests = generate(CONFIG, self.root / "fixture")
        self.metadata = self.root / "fixture/metadata.json"
        self.warehouse = ReferenceWarehouse()
        self.manifest = json.loads(self.manifests[0].read_text(encoding="utf-8"))
        self.tenant = self.manifest["tenant_id"]
        self.currency = self.manifest["currency"]

    def tearDown(self):
        self.warehouse.close()
        self.temporary.cleanup()

    def amend(self, mutate):
        path = self.manifests[0]
        manifest = json.loads(path.read_text(encoding="utf-8"))
        mutate(manifest)
        path.write_text(json.dumps(manifest), encoding="utf-8")

    def amend_page(self, mutate):
        manifest = json.loads(self.manifests[0].read_text(encoding="utf-8"))
        page = manifest["pages"][0]
        path = self.manifests[0].parent / page["file"]
        rows = [json.loads(line) for line in gzip.decompress(path.read_bytes()).decode().splitlines()]
        mutate(rows)
        path.write_bytes(gzip.compress(("\n".join(json.dumps(row) for row in rows) + "\n").encode(), mtime=0))
        page["sha256"] = hashlib.sha256(path.read_bytes()).hexdigest()
        manifest["row_count"] += len(rows) - page["row_count"]
        page["row_count"] = len(rows)
        self.manifests[0].write_text(json.dumps(manifest), encoding="utf-8")

    def test_generator_is_reproducible_and_raw_is_immutable(self):
        other = generate(CONFIG, self.root / "other")
        self.assertEqual(self.manifests[0].read_bytes(), other[0].read_bytes())
        metadata = json.loads(self.metadata.read_text(encoding="utf-8"))
        self.assertEqual([len(metadata[key]) for key in ("tenants", "accounts", "campaigns", "keywords")],
                         [2, 4, 20, 200])
        with self.assertRaises(FileExistsError):
            generate(CONFIG, self.root / "fixture")

    def test_replay_does_not_double_count_and_correction_replaces(self):
        self.warehouse.publish(self.manifests[0], self.metadata)
        before = self.warehouse.summary(self.tenant, self.currency)
        self.assertEqual(self.warehouse.publish(self.manifests[0], self.metadata)["status"], "REPLAY")
        self.assertEqual(before, self.warehouse.summary(self.tenant, self.currency))
        corrected = generate(CONFIG, self.root / "fixture", "correction")
        self.warehouse.publish(corrected[0], self.metadata)
        after = self.warehouse.summary(self.tenant, self.currency)
        self.assertEqual(before["cost"], after["cost"])
        self.assertNotEqual(before["conversions"], after["conversions"])

    def test_complete_empty_snapshot_removes_only_its_scope(self):
        for manifest in self.manifests:
            self.warehouse.publish(manifest, self.metadata)
        second_tenant = json.loads(self.manifests[2].read_text(encoding="utf-8"))["tenant_id"]
        untouched = self.warehouse.summary(second_tenant, self.currency)
        empty = generate(CONFIG, self.root / "fixture", "empty")
        self.warehouse.publish(empty[0], self.metadata)
        result = self.warehouse.summary(self.tenant, self.currency)
        self.assertEqual(result["cost"], "0")
        self.assertIsNone(result["cpa"])
        self.assertEqual(untouched, self.warehouse.summary(second_tenant, self.currency))
        self.assertNotEqual(self.warehouse.summary(self.tenant, "USD")["cost"], "0")

    def test_partial_and_rejected_snapshots_preserve_previous_fact(self):
        self.warehouse.publish(self.manifests[0], self.metadata)
        before = self.warehouse.summary(self.tenant, self.currency)
        partial = generate(CONFIG, self.root / "fixture", "partial")
        with self.assertRaisesRegex(QualityError, "INCOMPLETE_SNAPSHOT"):
            self.warehouse.publish(partial[0], self.metadata)
        self.amend_page(lambda rows: rows[-1].update(clicks=-1))
        with self.assertRaisesRegex(QualityError, "clicks"):
            self.warehouse.publish(self.manifests[0], self.metadata)
        self.assertEqual(before, self.warehouse.summary(self.tenant, self.currency))

    def test_scope_and_checksum_mismatch_fail_closed(self):
        self.amend(lambda manifest: manifest.update(currency="EUR"))
        with self.assertRaisesRegex(QualityError, "ACCOUNT_SCOPE_MISMATCH"):
            list(normalized_rows(self.manifests[0], self.metadata))
        self.amend(lambda manifest: manifest.update(currency=self.currency))
        page = self.manifests[0].parent / self.manifest["pages"][0]["file"]
        page.write_bytes(page.read_bytes() + b"tampered")
        with self.assertRaisesRegex(QualityError, "CHECKSUM_MISMATCH"):
            list(normalized_rows(self.manifests[0], self.metadata))

    def test_row_cannot_cross_tenant_or_campaign_mapping(self):
        self.amend_page(lambda rows: rows[0].update(tenant_id="different-tenant"))
        with self.assertRaisesRegex(QualityError, "ROW_SCOPE_MISMATCH"):
            self.warehouse.publish(self.manifests[0], self.metadata)

    def test_campaign_identity_and_dates_are_validated(self):
        self.amend_page(lambda rows: rows[0].update(source_campaign_id="wrong-source"))
        with self.assertRaisesRegex(QualityError, "CAMPAIGN_MAPPING_MISMATCH"):
            list(normalized_rows(self.manifests[0], self.metadata))
        self.amend_page(lambda rows: rows[0].update(source_campaign_id="0000", report_date="2026-01-01"))
        with self.assertRaisesRegex(QualityError, "REPORT_DATE_OUT_OF_SCOPE"):
            list(normalized_rows(self.manifests[0], self.metadata))

    def test_page_paths_and_numeric_precision_are_rejected(self):
        self.amend_page(lambda rows: rows[0].update(conversions=0.1))
        with self.assertRaisesRegex(QualityError, "exact decimal text"):
            list(normalized_rows(self.manifests[0], self.metadata))
        self.amend_page(lambda rows: rows[0].update(conversions="0.0000000001"))
        with self.assertRaisesRegex(QualityError, "9 decimal places"):
            list(normalized_rows(self.manifests[0], self.metadata))
        self.amend(lambda manifest: manifest["pages"][0].update(file="../page-00001.jsonl.gz"))
        with self.assertRaisesRegex(QualityError, "INVALID_PAGE_PATH"):
            list(normalized_rows(self.manifests[0], self.metadata))

    def test_stale_run_cannot_overwrite_newer_run(self):
        corrected = generate(CONFIG, self.root / "fixture", "correction")
        self.warehouse.publish(corrected[0], self.metadata)
        before = self.warehouse.summary(self.tenant, self.currency)
        with self.assertRaisesRegex(QualityError, "STALE_RUN"):
            self.warehouse.publish(self.manifests[0], self.metadata)
        self.assertEqual(before, self.warehouse.summary(self.tenant, self.currency))

    def test_duplicate_grain_deduplicates_but_conflicts_are_rejected(self):
        self.amend_page(lambda rows: rows.append(dict(rows[0])))
        self.assertEqual(self.warehouse.publish(self.manifests[0], self.metadata)["rows"], 300)
        self.amend_page(lambda rows: rows[-1].update(conversions="9.5"))
        with self.assertRaisesRegex(QualityError, "CONFLICTING_DUPLICATE_GRAIN"):
            self.warehouse.publish(self.manifests[0], self.metadata)

    def test_decimal_is_exact_and_kpis_use_sums(self):
        self.amend_page(lambda rows: rows[0].update(cost_micros=1, conversions="100.125"))
        rows = list(normalized_rows(self.manifests[0], self.metadata))
        self.assertEqual(rows[0]["cost"], "0.000001")
        # Conversions may exceed clicks; attribution is fractional/platform-specific.
        self.assertEqual(rows[0]["conversions"], "100.125")
        self.warehouse.publish(self.manifests[0], self.metadata)
        from decimal import Decimal, localcontext
        summary = self.warehouse.summary(self.tenant, self.currency)
        with localcontext() as context:
            context.prec = 38
            self.assertEqual(Decimal(summary["cpa"]), Decimal(summary["cost"]) / Decimal(summary["conversions"]))
        with self.assertRaisesRegex(QualityError, "CURRENCY_REQUIRED"):
            self.warehouse.summary(self.tenant, None)

    def test_parquet_has_explicit_decimal_schema_and_empty_is_valid(self):
        import pyarrow.parquet as pq
        normalized = normalize_to_parquet(self.manifests[0], self.metadata, self.root / "normalized")
        table = pq.read_table(normalized.parent / "campaign.parquet")
        self.assertEqual(table.num_rows, 300)
        self.assertEqual(str(table.schema.field("cost").type), "decimal128(38, 9)")
        empty = generate(CONFIG, self.root / "fixture", "empty")
        result = normalize_to_parquet(empty[0], self.metadata, self.root / "normalized")
        self.assertEqual(pq.read_table(result.parent / "campaign.parquet").num_rows, 0)

    def test_failed_normalization_has_rejection_evidence_and_no_complete_manifest(self):
        self.amend_page(lambda rows: rows[0].update(conversions="NaN"))
        output = self.root / "normalized"
        with self.assertRaisesRegex(QualityError, "NUMERIC"):
            normalize_to_parquet(self.manifests[0], self.metadata, output)
        destination = output / f"run={self.manifest['run_id']}"
        self.assertTrue((destination / "rejected.json").exists())
        self.assertFalse((destination / "manifest.json").exists())


if __name__ == "__main__":
    unittest.main()
