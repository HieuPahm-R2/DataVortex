import gzip
import json
import tempfile
import unittest
import subprocess
import sys
import time
from pathlib import Path
from pipeline.fixtures import generate
from pipeline.fetch import fetch_snapshot
from pipeline.mock_source import serve
from pipeline.normalize import normalize_to_parquet
from pipeline.local_worker import execute


class FetchTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.fixtures = self.root / 'fixture'
        paths = generate('data/fixtures/small.json', self.fixtures)
        scope = json.loads(paths[0].read_text())
        self.metadata = json.loads((self.fixtures / 'metadata.json').read_text(encoding='utf-8'))
        account = next(a for a in self.metadata['accounts'] if a['id'] == scope['account_id'])
        self.request = {**scope, 'source_account_id': account['source_account_id'], 'metadata': self.metadata}

    def test_partial_timeout_and_rate_limit_keep_evidence_and_retry_in_fresh_paths(self):
        for scenario, expected in [('partial', 'PARTIAL_PAGINATION'), ('timeout', 'SOURCE_UNAVAILABLE'),
                                   ('rate-limit', 'SOURCE_HTTP_429')]:
            with self.subTest(scenario=scenario), serve(self.fixtures, scenario=scenario, delay=.2 if scenario == 'timeout' else 0) as endpoint:
                first = fetch_snapshot(self.request, endpoint, self.root / 'worker', timeout=.05)
                second = fetch_snapshot(self.request, endpoint, self.root / 'worker', timeout=.05)
                self.assertFalse(first['complete'])
                self.assertTrue(first['retryable'])
                self.assertEqual(first['error_code'], expected)
                self.assertNotEqual(first['raw_manifest'], second['raw_manifest'])
                self.assertEqual(json.loads(Path(first['raw_manifest']).read_text())['errors'], [expected])
                if scenario == 'partial':
                    self.assertEqual(first['rows_received'], 100)
                if scenario == 'rate-limit':
                    self.assertEqual(first['retry_after'], 2)

    def test_invalid_scope_retains_raw_row_and_location_without_complete_parquet(self):
        for field, invalid in [('tenant_id', 'foreign'), ('account_id', 'foreign'),
                               ('report_date', '2020-01-01'), ('currency', 'EUR')]:
            with self.subTest(field=field), serve(self.fixtures) as endpoint:
                source = self.root / f'{field}.json'
                source.write_text(json.dumps(self.request), encoding='utf-8')
                fetched = execute('fetch', source, self.root / 'worker', endpoint, 2)
                manifest_path = Path(fetched['raw_manifest'])
                manifest = json.loads(manifest_path.read_text())
                page = manifest_path.parent / manifest['pages'][0]['file']
                # Corrupt a source row while retaining a valid source-page checksum.
                with gzip.open(page, 'rt', encoding='utf-8') as stream:
                    rows = [json.loads(line) for line in stream]
                rows[0][field] = invalid
                payload = gzip.compress(''.join(json.dumps(r)+'\n' for r in rows).encode(), mtime=0)
                page.write_bytes(payload)
                import hashlib
                manifest['pages'][0]['sha256'] = hashlib.sha256(payload).hexdigest()
                manifest_path.write_text(json.dumps(manifest))
                result = execute('normalize', manifest_path, self.root / 'worker', '', 2)
                self.assertEqual(result['error_code'], 'QUALITY_REJECTED')
                self.assertEqual(result['rows_rejected'], 1)
                rejection = json.loads(Path(result['rejected_uri']).read_text())
                self.assertEqual(rejection['page'], 'page-00001.jsonl.gz')
                self.assertEqual(rejection['line'], 1)
                self.assertEqual(rejection['raw_row'][field], invalid)
                self.assertFalse(Path(result['rejected_uri']).with_name('manifest.json').exists())
                self.assertTrue(manifest_path.exists())

    def test_killed_fetch_keeps_receipts_and_restart_uses_new_run(self):
        with serve(self.fixtures, page_size=1, delay=.05) as endpoint:
            source = self.root / 'request.json'
            source.write_text(json.dumps(self.request), encoding='utf-8')
            output = self.root / 'worker'
            process = subprocess.Popen([sys.executable, '-m', 'pipeline.local_worker', '--action', 'fetch',
                '--input', str(source), '--output', str(output), '--endpoint', endpoint,
                '--result', str(self.root / 'result.json')], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            try:
                deadline = time.monotonic() + 5
                receipts = []
                while time.monotonic() < deadline and not receipts:
                    receipts = list(output.glob('raw/**/receipt-00001.json'))
                    time.sleep(.01)
                self.assertTrue(receipts)
                process.kill()
                process.wait(timeout=5)
                abandoned = receipts[0].parent
                self.assertTrue((abandoned / 'started.json').exists())
                self.assertFalse((abandoned / 'manifest.json').exists())
            finally:
                if process.poll() is None:
                    process.kill()
                    process.wait(timeout=5)
        with serve(self.fixtures, page_size=100) as endpoint:
            restarted = fetch_snapshot(self.request, endpoint, output, timeout=2)
            self.assertTrue(restarted['complete'])
            self.assertNotEqual(Path(restarted['raw_manifest']).parent, abandoned)
            self.assertTrue(receipts[0].exists())

    def test_rest_and_soap_pages_become_verified_parquet(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifests = generate('data/fixtures/small.json', root / 'fixture')
            metadata = root / 'fixture/metadata.json'
            registry = json.loads(metadata.read_text(encoding='utf-8'))
            with serve(root / 'fixture', page_size=17) as endpoint:
                for original in manifests[:2]:
                    scope = json.loads(original.read_text(encoding='utf-8'))
                    account = next(a for a in registry['accounts'] if a['id'] == scope['account_id'])
                    request = {**scope, 'source_account_id': account['source_account_id']}
                    result = fetch_snapshot(request, endpoint, root / 'worker', timeout=2)
                    self.assertTrue(result['complete'])
                    path = Path(result['raw_manifest'])
                    manifest = json.loads(path.read_text())
                    self.assertGreater(manifest['page_count'], 1)
                    self.assertEqual(manifest['row_count'], 300)
                    with gzip.open(path.parent / manifest['pages'][0]['file'], 'rt', encoding='utf-8') as page:
                        self.assertEqual(json.loads(next(page))['account_id'], account['id'])
                    normalized = normalize_to_parquet(path, metadata, root / 'normalized')
                    self.assertEqual(json.loads(normalized.read_text())['normalized_row_count'], 300)


if __name__ == '__main__':
    unittest.main()
