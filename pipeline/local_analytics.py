"""Development warehouse read adapter. Main database is always opened read-only.

Uses the validated snapshot publisher in pipeline.reference. No Google credentials
or MySQL/cloud checkpoint writes are performed here.
"""
import argparse
import json
import sqlite3
from datetime import date, datetime, timezone
from decimal import Decimal, localcontext
from pathlib import Path

METRICS = ("impressions", "clicks", "conversions", "cost", "conversion_value")
RATIOS = (("ctr", "clicks", "impressions"), ("cpc", "cost", "clicks"),
          ("cpa", "cost", "conversions"), ("roas", "conversion_value", "cost"),
          ("cvr", "conversions", "clicks"))


def empty():
    return {key: Decimal(0) for key in METRICS}


def rendered(values):
    result = {key: format(value, "f") for key, value in values.items()}
    result["conversionValue"] = result.pop("conversion_value")
    with localcontext() as context:
        context.prec = 38  # Match the existing reference model for repeating ratios.
        for key, numerator, denominator in RATIOS:
            value = values[numerator] / values[denominator] if values[denominator] else None
            result[key] = format(value, "f") if value is not None else None
    return result


def query(database, request):
    start, end = date.fromisoformat(request["dateFrom"]), date.fromisoformat(request["dateTo"])
    if start > end or (end - start).days >= 366:
        raise ValueError("INVALID_DATE_RANGE")
    tenant = request["tenantId"]
    accounts = request["accounts"]
    # Parameter-bound temporary metadata scope is supplied by the authenticated Java adapter.
    # A read-only main database still permits private temporary tables.
    connection = sqlite3.connect(Path(database).resolve().as_uri() + "?mode=ro", uri=True, timeout=5)
    try:
        connection.execute("CREATE TEMP TABLE allowed(account TEXT, campaign TEXT, PRIMARY KEY(account,campaign))")
        connection.executemany("INSERT INTO allowed VALUES (?,?)",
                              ((a["accountId"], campaign) for a in accounts for campaign in a["campaignIds"]))
        connection.commit()
        connection.execute("BEGIN")
        currencies = sorted({a["currency"] for a in accounts})
        totals = {currency: empty() for currency in currencies}
        days = {currency: {} for currency in currencies}
        with localcontext() as context:
            context.prec = 76  # Exact NUMERIC sums, then decimal division; never SQLite REAL/SUM.
            for day, currency, payload in connection.execute(
                    "SELECT f.report_date,f.currency,f.payload FROM facts f JOIN allowed a "
                    "ON a.account=f.account AND a.campaign=f.campaign "
                    "WHERE f.tenant=? AND f.report_date BETWEEN ? AND ? ORDER BY f.report_date",
                    (tenant, start.isoformat(), end.isoformat())):
                if currency not in totals:
                    raise ValueError("ACCOUNT_CURRENCY_MISMATCH")
                row = json.loads(payload)
                daily = days[currency].setdefault(day, empty())
                for key in METRICS:
                    value = Decimal(row[key])
                    if not value.is_finite() or value < 0:
                        raise ValueError("INVALID_PERSISTED_METRIC")
                    totals[currency][key] += value
                    daily[key] += value
            watermarks = []
            selected = {a["accountId"] for a in accounts}
            for account, sequence, run, fetched in connection.execute(
                    "SELECT account,sequence,run_id,data_as_of FROM publications WHERE tenant=? ORDER BY account", (tenant,)):
                if account in selected:
                    instant = datetime.fromisoformat(fetched.replace("Z", "+00:00"))
                    if instant.tzinfo is None:
                        raise ValueError("INVALID_PUBLICATION_TIME")
                    fetched = instant.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")
                    watermarks.append({"accountId": account, "runSequence": sequence,
                                       "publishedRunId": run, "dataAsOf": fetched})
            return {"source": "LOCAL", "dataAsOf": min(
                        (w["dataAsOf"] for w in watermarks), default=None),
                    "inputWatermark": watermarks,
                    "groups": [{"currency": currency, "totals": rendered(totals[currency]),
                                "days": [{"date": day, "metrics": rendered(values)}
                                         for day, values in sorted(days[currency].items())]
                                if request.get("daily", False) else []}
                               for currency in currencies]}
    finally:
        connection.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database", required=True)
    parser.add_argument("--input", required=True)
    parser.add_argument("--result", required=True)
    args = parser.parse_args()
    try:
        result = query(args.database, json.loads(Path(args.input).read_text(encoding="utf-8")))
    except (OSError, ValueError, KeyError, TypeError, ArithmeticError, sqlite3.Error):
        result = {"errorCode": "WAREHOUSE_UNAVAILABLE"}
    with Path(args.result).open("x", encoding="utf-8") as stream:
        json.dump(result, stream, ensure_ascii=False)


if __name__ == "__main__":
    main()

