import gzip
import hashlib
import json
import random
import uuid
from datetime import date, datetime, timedelta, timezone
from decimal import Decimal
from pathlib import Path

NAMESPACE = uuid.UUID("b20e429f-b0dc-49e5-bda9-a70e21e66c41")


def identity(value):
    return str(uuid.uuid5(NAMESPACE, value))


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2, sort_keys=True)
        stream.write("\n")


def generate(config_path, output, scenario="baseline"):
    """Write deterministic, immutable raw runs. Metrics are simulated, not serving data."""
    config = json.loads(Path(config_path).read_text(encoding="utf-8"))
    output = Path(output)
    if scenario not in {"baseline", "correction", "empty", "partial"}:
        raise ValueError("Unknown fixture scenario")
    if config["days"] < 1 or config["page_size"] < 1:
        raise ValueError("days and page_size must be positive")
    start = date.fromisoformat(config["date_from"])
    end = start + timedelta(days=config["days"] - 1)
    sequence = {"baseline": 1, "correction": 2, "empty": 3, "partial": 4}[scenario]
    fetched = datetime.combine(end + timedelta(days=1), datetime.min.time(), timezone.utc)
    fetched += timedelta(hours=sequence)
    fetched_at = fetched.isoformat().replace("+00:00", "Z")
    registry = {"generator_version": config["generator_version"], "seed": config["seed"],
                "tenants": [], "accounts": [], "campaigns": [], "ad_groups": [], "keywords": []}
    manifests = []
    for tenant_index in range(config["tenants"]):
        tenant_id = identity(f"tenant-{tenant_index}")
        registry["tenants"].append({"id": tenant_id, "name": f"Advertiser {tenant_index + 1}"})
        for platform in config["platforms"]:
            account_id = identity(f"{tenant_id}/{platform}")
            currency = "VND" if platform == "MOCK_REST" else "USD"
            tz = "Asia/Ho_Chi_Minh" if currency == "VND" else "America/New_York"
            registry["accounts"].append({"id": account_id, "tenant_id": tenant_id,
                "platform": platform, "source_account_id": f"000{tenant_index}-{platform}",
                "currency": currency, "timezone": tz})
            rows = []
            rng = random.Random(f"{config['seed']}/{account_id}")
            for campaign_index in range(config["campaigns_per_account"]):
                campaign_id = identity(f"{account_id}/campaign-{campaign_index}")
                source_id = f"000{campaign_index}"
                registry["campaigns"].append({"id": campaign_id, "tenant_id": tenant_id,
                    "account_id": account_id, "source_campaign_id": source_id,
                    "name": f"Giày chạy bộ {campaign_index + 1}", "source_status": "ENABLED"})
                group_id = identity(f"{campaign_id}/group")
                registry["ad_groups"].append({"id": group_id, "tenant_id": tenant_id,
                    "campaign_id": campaign_id, "source_ad_group_id": f"group-{source_id}"})
                for keyword_index in range(config["keywords_per_campaign"]):
                    registry["keywords"].append({"id": identity(f"{group_id}/keyword-{keyword_index}"),
                        "tenant_id": tenant_id, "campaign_id": campaign_id, "ad_group_id": group_id,
                        "source_keyword_id": f"000{keyword_index}", "text": f"giày chạy bộ {keyword_index}",
                        "match_type": "PHRASE", "status": "ENABLED", "bid_micros": 1_000_000})
                for offset in range(config["days"]):
                    report_date = start + timedelta(days=offset)
                    seasonal = 1.25 if report_date.weekday() >= 5 else 1.0
                    impressions = int((1000 + offset * 4 + rng.randrange(200)) * seasonal)
                    clicks = impressions // 20
                    conversions = Decimal(clicks) / Decimal(10)
                    if scenario == "correction" and offset >= config["days"] - 7:
                        conversions += Decimal("0.5")
                    cost_micros = clicks * (25000 if currency == "VND" else 2) * 1_000_000
                    rows.append({"tenant_id": tenant_id, "platform": platform, "account_id": account_id,
                        "campaign_id": campaign_id, "source_campaign_id": source_id,
                        "report_date": report_date.isoformat(), "account_timezone": tz, "currency": currency,
                        "impressions": impressions, "clicks": clicks, "conversions": str(conversions),
                        "cost_micros": cost_micros, "conversion_value_micros": cost_micros * 3})
            if scenario == "empty":
                rows = []
            if scenario == "partial":
                rows = rows[:config["page_size"]]
            run_id = identity(f"{account_id}/{config['seed']}/{start}/{end}/{scenario}/v{config['generator_version']}")
            directory = (output / "raw" / f"platform={platform}" / f"tenant={tenant_id}" /
                         f"account={account_id}" / "report=CAMPAIGN" / f"run={run_id}")
            directory.mkdir(parents=True, exist_ok=False)
            pages = []
            for index, offset in enumerate(range(0, len(rows), config["page_size"]), 1):
                page = rows[offset:offset + config["page_size"]]
                payload = "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in page)
                compressed = gzip.compress(payload.encode("utf-8"), mtime=0)
                name = f"page-{index:05d}.jsonl.gz"
                (directory / name).write_bytes(compressed)
                pages.append({"file": name, "row_count": len(page),
                              "sha256": hashlib.sha256(compressed).hexdigest()})
            manifest = {"run_id": run_id, "run_sequence": sequence, "tenant_id": tenant_id,
                "account_id": account_id, "platform": platform, "report_kind": "CAMPAIGN",
                "date_from": start.isoformat(), "date_to": end.isoformat(),
                "schema_version": config["schema_version"], "generator_version": config["generator_version"],
                "seed": config["seed"], "fetched_at": fetched_at, "currency": currency,
                "account_timezone": tz, "complete": scenario != "partial",
                "page_count": len(pages), "row_count": len(rows), "pages": pages,
                "errors": ["SIMULATED_PARTIAL_PAGINATION"] if scenario == "partial" else []}
            path = directory / "manifest.json"
            write_json(path, manifest)
            manifests.append(path)
    registry_path = output / "metadata.json"
    if registry_path.exists():
        if json.loads(registry_path.read_text(encoding="utf-8")) != registry:
            raise ValueError("Existing metadata belongs to a different fixture configuration")
    else:
        write_json(registry_path, registry)
    return manifests
