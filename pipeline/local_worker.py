"""Subprocess boundary used by the Java local worker (result files contain safe codes)."""
import argparse
import json
import uuid
from pathlib import Path
from pipeline.fetch import fetch_snapshot
from pipeline.fixtures import write_json
from pipeline.normalize import normalize_to_parquet, QualityError


def execute(action, source, output, endpoint, timeout):
    if action == 'fetch':
        request = json.loads(Path(source).read_text(encoding='utf-8'))
        result = fetch_snapshot(request, endpoint, output, timeout)
        return result
    raw = Path(source)
    manifest = json.loads(raw.read_text(encoding='utf-8'))
    if str(uuid.UUID(manifest['run_id'])) != manifest['run_id']:
        raise ValueError('INVALID_RUN_UUID')
    base = {'raw_manifest': str(raw.resolve()), 'rows_received': manifest['row_count'],
            'rows_rejected': 0, 'retryable': False, 'retry_after': 1}
    try:
        normalized = normalize_to_parquet(raw, raw.parent / 'metadata.json', Path(output) / 'normalized')
        return {**base, 'normalized_manifest': str(normalized.resolve())}
    except (QualityError, ValueError, KeyError, TypeError, OSError) as error:
        rejected = Path(output) / 'normalized' / f"run={manifest['run_id']}" / 'rejected.json'
        # normalize_to_parquet owns the detailed diagnostic; preserve pre-validation failures too.
        if not rejected.exists():
            write_json(rejected, {'complete': False, 'raw_manifest': str(raw.resolve()),
                                 'error': str(error), 'rows_processed': 0})
        return {**base, 'error_code': 'QUALITY_REJECTED', 'rows_rejected': 1,
                'rejected_uri': str(rejected.resolve())}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--action', choices=['fetch', 'normalize'], required=True)
    parser.add_argument('--input', required=True)
    parser.add_argument('--output', required=True)
    parser.add_argument('--result', required=True)
    parser.add_argument('--endpoint', default='')
    parser.add_argument('--timeout', type=float, default=30)
    args = parser.parse_args()
    try:
        result = execute(args.action, args.input, args.output, args.endpoint, args.timeout)
    except (OSError, ValueError, KeyError, TypeError):
        result = {'error_code': 'PIPELINE_INPUT_OR_IO_ERROR', 'retryable': False}
    write_json(Path(args.result), result)
