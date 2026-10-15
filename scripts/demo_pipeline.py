"""Run a fresh, owned local fixture -> Parquet -> reference publication scenario."""
import argparse
import json
from pathlib import Path
from pipeline.fixtures import generate, write_json
from pipeline.normalize import QualityError, normalize_to_parquet
from pipeline.reference import ReferenceWarehouse


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True, help="New directory; existing directories are refused")
    args = parser.parse_args()
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=False)
    baseline = generate("data/fixtures/small.json", output / "fixtures")
    metadata = output / "fixtures/metadata.json"
    warehouse = ReferenceWarehouse(output / "reference.sqlite")
    try:
        normalized_count = 0
        for manifest in baseline:
            normalize_to_parquet(manifest, metadata, output / "normalized")
            warehouse.publish(manifest, metadata)
            normalized_count += json.loads(manifest.read_text(encoding="utf-8"))["row_count"]
        selected = json.loads(baseline[0].read_text(encoding="utf-8"))
        before = warehouse.summary(selected["tenant_id"], selected["currency"])
        replay = warehouse.publish(baseline[0], metadata)
        corrected = generate("data/fixtures/small.json", output / "fixtures", "correction")
        for manifest in corrected:
            normalize_to_parquet(manifest, metadata, output / "normalized")
            warehouse.publish(manifest, metadata)
        after = warehouse.summary(selected["tenant_id"], selected["currency"])
        if replay["status"] != "REPLAY" or before["cost"] != after["cost"] or before["conversions"] == after["conversions"]:
            raise AssertionError("Replay/correction scenario failed")
        partial = generate("data/fixtures/small.json", output / "fixtures", "partial")
        try:
            warehouse.publish(partial[0], metadata)
        except QualityError as error:
            partial_error = str(error)
        else:
            raise AssertionError("Partial snapshot was published")
        if warehouse.summary(selected["tenant_id"], selected["currency"]) != after:
            raise AssertionError("Partial snapshot changed facts")
        empty = generate("data/fixtures/small.json", output / "fixtures", "empty")
        normalize_to_parquet(empty[0], metadata, output / "normalized")
        warehouse.publish(empty[0], metadata)
        result = {"baseline_rows": normalized_count, "raw_runs": 16, "normalized_runs": 9,
            "replay": replay["status"], "baseline": before, "corrected": after,
            "partial_rejected": partial_error,
            "empty_snapshot": warehouse.summary(selected["tenant_id"], selected["currency"]),
            "runtime": "local SQLite oracle and actual Parquet; cloud execution unverified"}
        write_json(output / "evidence.json", result)
        print(json.dumps({"evidence": str(output / "evidence.json"), "baseline_rows": normalized_count,
                          "replay": replay["status"], "partial_rejected": True}, sort_keys=True))
    finally:
        warehouse.close()


if __name__ == "__main__":
    main()
