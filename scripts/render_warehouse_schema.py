"""Render reviewed BigQuery DDL for a concrete project/dataset. Does not execute it."""
import argparse
import re
from pathlib import Path


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project', required=True)
    parser.add_argument('--dataset', required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    if not re.fullmatch(r'[a-z][a-z0-9-]{4,61}[a-z0-9]', args.project) or not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]{0,1023}', args.dataset):
        raise SystemExit('Invalid project/dataset identifier')
    source = Path(__file__).resolve().parents[1] / 'warehouse/bigquery/schema.sql'
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open('x', encoding='utf-8', newline='\n') as stream:
        stream.write(source.read_text(encoding='utf-8').replace('YOUR_PROJECT.adtech', args.project + '.' + args.dataset))
    print(output.resolve())
