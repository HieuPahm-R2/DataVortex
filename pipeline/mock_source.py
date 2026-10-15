"""Executable fixture-backed REST/SOAP mock; never used for production data."""
import argparse
import gzip
import json
import threading
import time
import xml.etree.ElementTree as ET
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit


def server(fixtures, host='127.0.0.1', port=0, scenario='baseline', page_size=100, delay=0):
    fixtures = Path(fixtures)
    registry = json.loads((fixtures / 'metadata.json').read_text(encoding='utf-8'))
    manifests = [json.loads(path.read_text(encoding='utf-8')) | {'directory': path.parent}
                 for path in fixtures.glob('raw/**/manifest.json')]

    class Handler(BaseHTTPRequestHandler):
        def handle(self):
            try:
                super().handle()
            except (ConnectionResetError, ConnectionAbortedError):
                pass

        def log_message(self, *args):
            pass

        def do_GET(self):
            fields = {k: v[0] for k, v in parse_qs(urlsplit(self.path).query, keep_blank_values=True).items()}
            self.reply(fields, False)

        def do_POST(self):
            root = ET.fromstring(self.rfile.read(int(self.headers['Content-Length'])))
            report = root.find('.//GetCampaignReport')
            self.reply({field.tag: field.text or '' for field in report}, True)

        def reply(self, fields, soap):
            if scenario == 'timeout':
                time.sleep(delay or 2)
            if scenario in ('rate-limit', 'unavailable'):
                self.send_response(429 if scenario == 'rate-limit' else 503)
                self.send_header('Retry-After', '2')
                self.end_headers()
                return
            account = next((a for a in registry['accounts'] if a['id'] == fields.get('account_id')
                            and a['source_account_id'] == fields.get('source_account_id')), None)
            snapshot = next((m for m in manifests if m['account_id'] == fields.get('account_id')), None)
            if account is None or snapshot is None:
                self.send_error(404)
                return
            rows = []
            for page in snapshot['pages']:
                with gzip.open(snapshot['directory'] / page['file'], 'rt', encoding='utf-8') as stream:
                    rows.extend(row for line in stream if fields['date_from'] <=
                                (row := json.loads(line))['report_date'] <= fields['date_to'])
            offset = int(fields.get('page') or '0')
            if scenario == 'empty':
                rows = []
            selected = rows[offset:offset + page_size]
            following = str(offset + page_size) if offset + page_size < len(rows) else None
            complete = following is None
            if scenario == 'partial':
                following, complete = None, False
            if delay and scenario != 'timeout':
                time.sleep(delay)
            if soap:
                envelope = ET.Element('{http://schemas.xmlsoap.org/soap/envelope/}Envelope')
                body = ET.SubElement(envelope, '{http://schemas.xmlsoap.org/soap/envelope/}Body')
                response = ET.SubElement(body, 'GetCampaignReportResponse')
                elements = ET.SubElement(response, 'rows')
                for row in selected:
                    element = ET.SubElement(elements, 'row')
                    for key, value in row.items():
                        ET.SubElement(element, key).text = str(value)
                ET.SubElement(response, 'next_page').text = following
                ET.SubElement(response, 'complete').text = str(complete).lower()
                payload, content_type = ET.tostring(envelope), 'text/xml'
            else:
                payload = json.dumps({'rows': selected, 'next_page': following, 'complete': complete}).encode()
                content_type = 'application/json'
            try:
                self.send_response(200)
                self.send_header('Content-Type', content_type)
                self.send_header('Content-Length', str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)
            except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
                pass

    return ThreadingHTTPServer((host, port), Handler)


@contextmanager
def serve(fixtures, **options):
    http = server(fixtures, **options)
    thread = threading.Thread(target=http.serve_forever, daemon=True)
    thread.start()
    try:
        yield f'http://127.0.0.1:{http.server_port}'
    finally:
        http.shutdown()
        http.server_close()
        thread.join()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fixtures', required=True)
    parser.add_argument('--port', type=int, default=8091)
    parser.add_argument('--scenario', choices=['baseline', 'empty', 'partial', 'timeout', 'rate-limit', 'unavailable'], default='baseline')
    args = parser.parse_args()
    http = server(args.fixtures, port=args.port, scenario=args.scenario)
    print(f'Mock source listening on http://127.0.0.1:{http.server_port}', flush=True)
    try:
        http.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        http.server_close()
