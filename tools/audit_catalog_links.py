#!/usr/bin/env python3
"""Audit reachability of catalogue URLs without claiming source verification.

A reachable HTTP endpoint only proves that an address responded. It does not
prove ownership, scientific quality, licensing, price, or the accuracy of the
catalogue description. Every output row therefore remains UNVERIFIED.
"""
from __future__ import annotations

import argparse
import csv
import json
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CATALOG = ROOT / "app/src/main/assets/knowledge_catalog.json"
USER_AGENT = "KnowledgeAtlasCatalogAudit/1.0 (+https://github.com/mhmdahmdbdalrhym615-tech/knowledge-atlas)"
TIMEOUT_SECONDS = 14
MAX_WORKERS = 10
READ_LIMIT = 1024


def validate_catalog(records: list[dict[str, Any]]) -> None:
    if not records:
        raise ValueError("catalogue contains no records")
    ids: set[str] = set()
    for index, row in enumerate(records, start=1):
        source_id = str(row.get("id", "")).strip()
        if not source_id or source_id in ids:
            raise ValueError(f"record {index}: missing or duplicate id {source_id!r}")
        ids.add(source_id)
        url = str(row.get("officialUrl", "")).strip()
        parsed = urlsplit(url)
        if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
            raise ValueError(f"record {source_id}: officialUrl must be a credential-free HTTPS URL")
        if not str(row.get("nameEn", "")).strip() or not str(row.get("nameAr", "")).strip():
            raise ValueError(f"record {source_id}: Arabic and English names are required")


def probe(row: dict[str, Any], checked_at: str) -> dict[str, Any]:
    url = str(row["officialUrl"]).strip()
    request = Request(
        url,
        headers={
            "User-Agent": USER_AGENT,
            "Accept": "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.5",
            "Range": "bytes=0-1023",
        },
        method="GET",
    )
    result: dict[str, Any] = {
        "id": row["id"],
        "nameAr": row.get("nameAr", ""),
        "nameEn": row.get("nameEn", ""),
        "categoryId": row.get("categoryId"),
        "officialUrl": url,
        "finalUrl": "",
        "httpStatus": None,
        "responseHost": "",
        "reachable": False,
        "auditStatus": "UNVERIFIED",
        "checkedAt": checked_at,
        "note": "HTTP reachability only; ownership, content, access, pricing, licensing and scientific quality remain unverified.",
    }
    try:
        with urlopen(request, timeout=TIMEOUT_SECONDS) as response:
            status = int(response.status)
            final_url = response.geturl()
            response.read(READ_LIMIT)
            final_parts = urlsplit(final_url)
            final_is_safe_https = (
                final_parts.scheme == "https"
                and bool(final_parts.hostname)
                and not final_parts.username
                and not final_parts.password
            )
            reachable = 200 <= status < 400 and final_is_safe_https
            result.update(
                finalUrl=final_url,
                httpStatus=status,
                responseHost=final_parts.hostname or "",
                reachable=reachable,
            )
            if reachable:
                result["auditStatus"] = "REACHABLE_UNVERIFIED"
            elif 200 <= status < 400 and not final_is_safe_https:
                result["auditStatus"] = "UNSAFE_REDIRECT_UNVERIFIED"
                result["note"] = "The endpoint redirected away from a credential-free HTTPS URL; manual review required."
            else:
                result["auditStatus"] = "HTTP_ERROR_UNVERIFIED"
    except HTTPError as exc:
        result.update(
            finalUrl=exc.geturl(),
            httpStatus=int(exc.code),
            responseHost=urlsplit(exc.geturl()).hostname or "",
            auditStatus="HTTP_ERROR_UNVERIFIED",
        )
        result["note"] = f"Server returned HTTP {exc.code}; manually review. No source verification was inferred."
    except (URLError, TimeoutError, OSError, ValueError) as exc:
        reason = getattr(exc, "reason", exc)
        result["auditStatus"] = "NETWORK_ERROR_UNVERIFIED"
        result["note"] = f"Could not confirm reachability: {str(reason)[:240]}. This is not proof the source is invalid."
    except Exception as exc:  # keep the entire audit report even for unusual servers
        result["auditStatus"] = "AUDIT_ERROR_UNVERIFIED"
        result["note"] = f"Unexpected probe error: {type(exc).__name__}: {str(exc)[:200]}"
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--catalog", type=Path, default=DEFAULT_CATALOG)
    parser.add_argument("--out-dir", type=Path, default=ROOT / "catalog-audit")
    parser.add_argument("--workers", type=int, default=MAX_WORKERS)
    args = parser.parse_args()

    payload = json.loads(args.catalog.read_text(encoding="utf-8"))
    records = payload.get("records")
    if not isinstance(records, list):
        raise ValueError("catalogue JSON must contain a records array")
    validate_catalog(records)
    checked_at = datetime.now(timezone.utc).isoformat()
    results: list[dict[str, Any]] = []

    with ThreadPoolExecutor(max_workers=max(1, min(args.workers, 20))) as pool:
        futures = [pool.submit(probe, row, checked_at) for row in records]
        for future in as_completed(futures):
            results.append(future.result())

    order = {str(row["id"]): index for index, row in enumerate(records)}
    results.sort(key=lambda row: order[str(row["id"])])
    args.out_dir.mkdir(parents=True, exist_ok=True)

    json_path = args.out_dir / "catalog-link-audit.json"
    csv_path = args.out_dir / "catalog-link-audit.csv"
    summary_path = args.out_dir / "catalog-link-audit-summary.json"

    json_path.write_text(
        json.dumps(
            {
                "catalogVersion": payload.get("catalogVersion"),
                "checkedAt": checked_at,
                "recordCount": len(results),
                "method": "HTTPS GET reachability probe; no ownership or content verification",
                "results": results,
            },
            ensure_ascii=False,
            indent=2,
        ) + "\n",
        encoding="utf-8",
    )
    fields = [
        "id", "nameAr", "nameEn", "categoryId", "officialUrl", "finalUrl",
        "httpStatus", "responseHost", "reachable", "auditStatus", "checkedAt", "note",
    ]
    with csv_path.open("w", newline="", encoding="utf-8-sig") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(results)

    counts: dict[str, int] = {}
    for row in results:
        counts[row["auditStatus"]] = counts.get(row["auditStatus"], 0) + 1
    summary = {
        "checkedAt": checked_at,
        "recordCount": len(results),
        "statusCounts": counts,
        "verifiedSources": 0,
        "warning": "Reachability is not source verification. All records must remain REVIEW_REQUIRED until manually reviewed.",
    }
    summary_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    print(f"Wrote {json_path} and {csv_path}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"catalog audit failed: {error}", file=sys.stderr)
        raise SystemExit(2)
