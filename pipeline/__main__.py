import argparse
import json
from pathlib import Path
from pipeline.fixtures import generate
from pipeline.normalize import normalize_to_parquet
from pipeline.reference import ReferenceWarehouse


def main():
    parser = argparse.ArgumentParser(description="AdInsight deterministic fixtures and raw replay")
    commands = parser.add_subparsers(dest="command", required=True)
    fixture = commands.add_parser("generate")
    fixture.add_argument("--config", default="data/fixtures/small.json")
    fixture.add_argument("--output", default="data/generated")
    fixture.add_argument("--scenario", choices=["baseline", "correction", "empty", "partial"], default="baseline")
    normalize = commands.add_parser("normalize")
    normalize.add_argument("--manifest", required=True)
    normalize.add_argument("--metadata", required=True)
    normalize.add_argument("--output", default="data/generated/normalized")
    replay = commands.add_parser("replay-local", help="SQLite reference model; does not write BigQuery")
    replay.add_argument("--manifest", required=True)
    replay.add_argument("--metadata", required=True)
    replay.add_argument("--warehouse", default=".local/reference.sqlite")
    args = parser.parse_args()
    if args.command == "generate":
        for path in generate(args.config, args.output, args.scenario):
            print(path)
    elif args.command == "normalize":
        print(normalize_to_parquet(args.manifest, args.metadata, args.output))
    else:
        Path(args.warehouse).parent.mkdir(parents=True, exist_ok=True)
        warehouse = ReferenceWarehouse(args.warehouse)
        try:
            print(json.dumps(warehouse.publish(args.manifest, args.metadata), sort_keys=True))
        finally:
            warehouse.close()


if __name__ == "__main__":
    main()
