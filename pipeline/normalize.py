import gzip
import hashlib
import json
import re
import uuid
from datetime import date, datetime
from decimal import Decimal, InvalidOperation, localcontext
from pathlib import Path


class QualityError(ValueError):
    """A snapshot failed a pre-publication quality gate (spec sections 8 and 10)."""


def fail(message):
    raise QualityError(message)


def integer(value, field, minimum=0):
    if type(value) is not int or not minimum <= value <= 2**63 - 1:
        fail(f"{field}: expected INT64 >= {minimum}")
    return value


def numeric(value, field):
    if not isinstance(value, (str, int, Decimal)) or isinstance(value, bool):
        fail(f"{field}: use exact decimal text, not a float")
    try:
        result = Decimal(value)
    except InvalidOperation:
        fail(f"{field}: invalid decimal")
    if not result.is_finite() or result < 0 or result >= Decimal(10)**29:
        fail(f"{field}: outside non-negative BigQuery NUMERIC range")
    if result.as_tuple().exponent < -9:
        fail(f"{field}: more than 9 decimal places")
    return result


def validate_manifest(manifest, registry):
    for key in ("run_id", "tenant_id", "account_id"):
        try:
            if str(uuid.UUID(manifest[key])) != manifest[key]:
                fail(f"{key}: non-canonical UUID")
        except (KeyError, ValueError, TypeError, AttributeError):
            fail(f"{key}: invalid UUID")
    if manifest.get("complete") is not True or manifest.get("errors") != []:
        fail("INCOMPLETE_SNAPSHOT: all pages must succeed before normalization/publication")
    if manifest.get("schema_version") != 1 or manifest.get("report_kind") != "CAMPAIGN":
        fail("UNSUPPORTED_SCHEMA: expected campaign schema 1")
    integer(manifest.get("run_sequence"), "run_sequence", 1)
    integer(manifest.get("row_count"), "row_count")
    integer(manifest.get("page_count"), "page_count")
    try:
        start, end = date.fromisoformat(manifest["date_from"]), date.fromisoformat(manifest["date_to"])
        fetched = datetime.fromisoformat(manifest["fetched_at"])
        if (start.isoformat() != manifest["date_from"] or end.isoformat() != manifest["date_to"]
                or start > end or fetched.utcoffset() is None or fetched.utcoffset().total_seconds() != 0):
            fail("INVALID_TIME_SCOPE: require ordered dates and UTC fetched_at")
    except (KeyError, ValueError, TypeError):
        fail("INVALID_TIME_SCOPE: require ordered dates and UTC fetched_at")
    account = next((a for a in registry["accounts"] if a["id"] == manifest["account_id"]), None)
    if account is None:
        fail("UNKNOWN_ACCOUNT")
    for field, metadata_field in (("tenant_id", "tenant_id"), ("platform", "platform"),
                                  ("currency", "currency"), ("account_timezone", "timezone")):
        if manifest.get(field) != account[metadata_field]:
            fail(f"ACCOUNT_SCOPE_MISMATCH: {field}")
    if manifest["platform"] not in {"MOCK_REST", "MOCK_SOAP", "GOOGLE_ADS"}:
        fail("UNSUPPORTED_PLATFORM")
    if not re.fullmatch(r"[A-Z]{3}", manifest["currency"]):
        fail("INVALID_CURRENCY")
    # Fixture account registry is the authority for currency and timezone.
    if not isinstance(manifest.get("pages"), list) or len(manifest["pages"]) != manifest["page_count"]:
        fail("PAGE_COUNT_MISMATCH")
    names = set()
    for page in manifest["pages"]:
        name = page.get("file", "")
        if not re.fullmatch(r"page-[0-9]{5}\.jsonl\.gz", name) or name in names:
            fail("INVALID_PAGE_PATH_OR_DUPLICATE")
        names.add(name)
        integer(page.get("row_count"), "page.row_count")
        if not re.fullmatch(r"[0-9a-f]{64}", page.get("sha256", "")):
            fail("INVALID_CHECKSUM")
    if sum(page["row_count"] for page in manifest["pages"]) != manifest["row_count"]:
        fail("ROW_COUNT_MISMATCH")
    return account


def normalized_rows(manifest_path, registry_path):
    """Stream validated rows; callers must exhaust all pages before publishing."""
    manifest_path = Path(manifest_path)
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    registry = json.loads(Path(registry_path).read_text(encoding="utf-8"))
    validate_manifest(manifest, registry)
    campaigns = {c["id"]: c for c in registry["campaigns"] if c["tenant_id"] == manifest["tenant_id"]
                 and c["account_id"] == manifest["account_id"]}
    for page in manifest["pages"]:
        path = manifest_path.parent / page["file"]
        checksum = hashlib.sha256()
        with path.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1 << 20), b""):
                checksum.update(chunk)
        if checksum.hexdigest() != page["sha256"]:
            fail("CHECKSUM_MISMATCH")
    for page in manifest["pages"]:
        received = 0
        with gzip.open(manifest_path.parent / page["file"], "rt", encoding="utf-8") as stream:
            for line in stream:
                received += 1
                try:
                    raw = json.loads(line)
                except json.JSONDecodeError:
                    fail("INVALID_JSON_ROW")
                for field in ("tenant_id", "platform", "account_id", "currency", "account_timezone"):
                    if raw.get(field) != manifest[field]:
                        fail(f"ROW_SCOPE_MISMATCH: {field}")
                campaign = campaigns.get(raw.get("campaign_id"))
                if campaign is None or raw.get("source_campaign_id") != campaign["source_campaign_id"]:
                    fail("CAMPAIGN_MAPPING_MISMATCH")
                try:
                    report_date = date.fromisoformat(raw["report_date"])
                except (KeyError, ValueError, TypeError):
                    fail("INVALID_REPORT_DATE")
                if not manifest["date_from"] <= report_date.isoformat() <= manifest["date_to"]:
                    fail("REPORT_DATE_OUT_OF_SCOPE")
                impressions = integer(raw.get("impressions"), "impressions")
                clicks = integer(raw.get("clicks"), "clicks")
                if clicks > impressions:
                    fail("CLICKS_EXCEED_IMPRESSIONS")
                conversions = numeric(raw.get("conversions"), "conversions")
                with localcontext() as context:
                    context.prec = 38
                    cost = Decimal(integer(raw.get("cost_micros"), "cost_micros")) / Decimal(1_000_000)
                    value = Decimal(integer(raw.get("conversion_value_micros"), "conversion_value_micros")) / Decimal(1_000_000)
                yield {"tenant_id": manifest["tenant_id"], "platform": manifest["platform"],
                    "account_id": manifest["account_id"], "campaign_id": raw["campaign_id"],
                    "source_campaign_id": raw["source_campaign_id"], "report_date": report_date.isoformat(),
                    "account_timezone": manifest["account_timezone"], "currency": manifest["currency"],
                    "impressions": impressions, "clicks": clicks, "conversions": str(conversions),
                    "cost": format(cost, "f"), "conversion_value": format(value, "f"),
                    "run_id": manifest["run_id"], "run_sequence": manifest["run_sequence"],
                    "fetched_at": manifest["fetched_at"], "is_partial": False, "schema_version": 1}
        if received != page["row_count"]:
            fail("PAGE_ROW_COUNT_MISMATCH")


def normalize_to_parquet(manifest_path, registry_path, output):
    """Stream bounded batches into Parquet; invalid runs remain unpublished with rejection evidence."""
    import pyarrow as pa
    import pyarrow.parquet as pq
    from pipeline.fixtures import write_json

    manifest = json.loads(Path(manifest_path).read_text(encoding="utf-8"))
    registry = json.loads(Path(registry_path).read_text(encoding="utf-8"))
    # Validate identifiers before deriving any filesystem path from the manifest.
    validate_manifest(manifest, registry)
    destination = Path(output) / f"run={manifest['run_id']}"
    destination.mkdir(parents=True, exist_ok=False)
    schema = pa.schema([
        *[(name, pa.string()) for name in ("tenant_id", "platform", "account_id", "campaign_id",
           "source_campaign_id", "account_timezone", "currency", "run_id")],
        ("report_date", pa.date32()), ("impressions", pa.int64()), ("clicks", pa.int64()),
        *[(name, pa.decimal128(38, 9)) for name in ("conversions", "cost", "conversion_value")],
        ("run_sequence", pa.int64()), ("fetched_at", pa.timestamp("us", tz="UTC")),
        ("is_partial", pa.bool_()), ("schema_version", pa.int64())])
    count = 0
    try:
        with pq.ParquetWriter(destination / "campaign.parquet", schema, compression="snappy") as writer:
            batch = []
            for row in normalized_rows(manifest_path, registry_path):
                row["report_date"] = date.fromisoformat(row["report_date"])
                row["fetched_at"] = datetime.fromisoformat(row["fetched_at"])
                for field in ("conversions", "cost", "conversion_value"):
                    row[field] = Decimal(row[field])
                batch.append(row)
                count += 1
                if len(batch) == 1000:
                    writer.write_table(pa.Table.from_pylist(batch, schema=schema))
                    batch.clear()
            if batch:
                writer.write_table(pa.Table.from_pylist(batch, schema=schema))
        write_json(destination / "manifest.json", {**manifest, "normalized_row_count": count,
            "rejected_count": 0, "normalized_file": "campaign.parquet", "complete": True,
            "normalized_sha256": hashlib.sha256((destination / "campaign.parquet").read_bytes()).hexdigest(),
            "raw_manifest_sha256": hashlib.sha256(Path(manifest_path).read_bytes()).hexdigest()})
    except (QualityError, KeyError, OSError, ValueError) as error:
        write_json(destination / "rejected.json", {"run_id": manifest["run_id"], "complete": False,
            "rows_processed": count, "error": str(error)})
        raise
    return destination / "manifest.json"
