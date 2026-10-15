"""Local mock connectors. A run is immutable and complete only at the last page."""
import gzip
import hashlib
import json
import socket
import uuid
import xml.etree.ElementTree as ET
from http.client import HTTPException
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, build_opener, HTTPRedirectHandler
from pipeline.fixtures import write_json


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def source_page(request, endpoint, cursor, timeout):
    fields = {key: request[key] for key in ('account_id', 'source_account_id', 'date_from', 'date_to')}
    fields['page'] = cursor
    if request['platform'] == 'MOCK_REST':
        http = Request(endpoint + '/rest/campaigns?' + urlencode(fields))
    elif request['platform'] == 'MOCK_SOAP':
        envelope = ET.Element('{http://schemas.xmlsoap.org/soap/envelope/}Envelope')
        body = ET.SubElement(envelope, '{http://schemas.xmlsoap.org/soap/envelope/}Body')
        report = ET.SubElement(body, 'GetCampaignReport')
        for key, value in fields.items():
            ET.SubElement(report, key).text = str(value)
        http = Request(endpoint + '/soap', ET.tostring(envelope),
                       {'Content-Type': 'text/xml; charset=utf-8', 'SOAPAction': 'GetCampaignReport'})
    else:
        raise ValueError('UNSUPPORTED_PLATFORM')
    with build_opener(NoRedirect()).open(http, timeout=timeout) as response:
        # A source page is bounded; never buffer the whole snapshot.
        payload = response.read(16 * 1024 * 1024 + 1)
        if len(payload) > 16 * 1024 * 1024:
            raise ValueError('SOURCE_PAGE_TOO_LARGE')
        if request['platform'] == 'MOCK_REST':
            return json.loads(payload)
        if b'<!DOCTYPE' in payload.upper() or b'<!ENTITY' in payload.upper():
            raise ValueError('UNSUPPORTED_XML')
        root = ET.fromstring(payload)
        report = root.find('.//GetCampaignReportResponse')
        if report is None:
            raise ValueError('SOAP_RESPONSE_INVALID')
        rows = []
        for element in report.findall('./rows/row'):
            row = {field.tag: field.text for field in element}
            for field in ('impressions', 'clicks', 'cost_micros', 'conversion_value_micros'):
                try:
                    row[field] = int(row[field])
                except (KeyError, TypeError, ValueError):
                    # Keep invalid source values verbatim for raw/quarantine evidence.
                    pass
            rows.append(row)
        return {'rows': rows, 'next_page': report.findtext('next_page') or None,
                'complete': report.findtext('complete') == 'true'}


def retry_after(value):
    try:
        return max(1, int(value))
    except (TypeError, ValueError):
        try:
            seconds = (parsedate_to_datetime(value) - datetime.now(timezone.utc)).total_seconds()
            return max(1, int(seconds) + 1)
        except (TypeError, ValueError, OverflowError):
            return 1


def fetch_snapshot(request, endpoint, output, timeout=30):
    # Paths come from our canonical job/account metadata, never a source cursor.
    for key in ('tenant_id', 'account_id'):
        if str(uuid.UUID(request[key])) != request[key]:
            raise ValueError('INVALID_SCOPE_UUID')
    if request['platform'] not in ('MOCK_REST', 'MOCK_SOAP') or request['report_kind'] != 'CAMPAIGN':
        raise ValueError('UNSUPPORTED_SCHEMA')
    run = str(uuid.uuid4())
    directory = (Path(output) / 'raw' / f"platform={request['platform']}" /
                 f"tenant={request['tenant_id']}" / f"account={request['account_id']}" /
                 'report=CAMPAIGN' / f'run={run}')
    directory.mkdir(parents=True, exist_ok=False)
    manifest = {key: request[key] for key in ('tenant_id', 'account_id', 'platform', 'report_kind',
                'date_from', 'date_to', 'run_sequence', 'currency', 'account_timezone')}
    manifest.update(run_id=run, schema_version=1, fetched_at=datetime.now(timezone.utc).isoformat(),
                    complete=False, pages=[], page_count=0, row_count=0, errors=[])
    for key in ('job_id', 'attempt'):
        if key in request:
            manifest[key] = request[key]
    # Survives abrupt process death; final manifest is written once at the end.
    write_json(directory / 'started.json', manifest)
    if 'metadata' in request:
        write_json(directory / 'metadata.json', request['metadata'])
    cursor, seen = '', set()
    result = {'complete': False, 'retryable': False, 'retry_after': 1, 'rows_rejected': 0}
    try:
        while True:
            if cursor in seen or len(seen) >= 99999:
                raise ValueError('PAGINATION_LOOP')
            seen.add(cursor)
            page = source_page(request, endpoint, cursor, timeout)
            rows, following = page['rows'], page['next_page']
            if not isinstance(rows, list):
                raise ValueError('INVALID_SOURCE_ROWS')
            if following is not None and (not isinstance(following, str) or not following):
                raise ValueError('INVALID_SOURCE_CURSOR')
            name = f"page-{len(manifest['pages']) + 1:05d}.jsonl.gz"
            payload = ''.join(json.dumps(row, ensure_ascii=False) + '\n' for row in rows).encode('utf-8')
            compressed = gzip.compress(payload, mtime=0)
            with (directory / name).open('xb') as stream:
                stream.write(compressed)
            manifest['pages'].append({'file': name, 'row_count': len(rows),
                                      'sha256': hashlib.sha256(compressed).hexdigest()})
            manifest['row_count'] += len(rows)
            manifest['page_count'] += 1
            # Immutable per-page receipt also records completed pages after a crash.
            write_json(directory / f"receipt-{manifest['page_count']:05d}.json", manifest['pages'][-1])
            if following is None:
                if page.get('complete') is not True:
                    result['retryable'] = True
                    raise ValueError('PARTIAL_PAGINATION')
                manifest['complete'] = True
                result['complete'] = True
                break
            cursor = following
    except HTTPError as error:
        result.update(error_code=f'SOURCE_HTTP_{error.code}', retryable=error.code in (408, 429) or error.code >= 500,
                      retry_after=retry_after(error.headers.get('Retry-After')))
        error.close()
    except (TimeoutError, socket.timeout, URLError, ConnectionError, HTTPException):
        result.update(error_code='SOURCE_UNAVAILABLE', retryable=True)
    except (ValueError, KeyError, TypeError, ET.ParseError):
        result.setdefault('error_code', 'SOURCE_SCHEMA_INVALID')
        if result['retryable']:
            result['error_code'] = 'PARTIAL_PAGINATION'
    except OSError:
        result.update(error_code='RAW_IO_ERROR', retryable=True)
    if not result['complete']:
        manifest['errors'] = [result['error_code']]
    path = directory / 'manifest.json'
    write_json(path, manifest)
    return {**result, 'raw_manifest': str(path.resolve()), 'rows_received': manifest['row_count']}
