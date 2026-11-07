"""SearXNG metasearch adapter for Knowledge Atlas.

The service performs conservative query expansion, result shaping and a
single-process request limit. For horizontally scaled deployments, replace
the in-memory limiter with a shared Redis/Valkey counter.
"""
from __future__ import annotations

import asyncio
import os
import time
from collections import deque
from datetime import datetime, timezone
from typing import Literal

import httpx
from fastapi import FastAPI, HTTPException, Request
from pydantic import BaseModel, Field

from .search_core import expand_query, rank_and_shape_results

app = FastAPI(
    title="Knowledge Atlas Search API",
    version="0.2.0",
    description=(
        "Query expansion and metasearch. Domain labels are not factual verification."
    ),
)

SEARXNG_BASE_URL = os.getenv("SEARXNG_BASE_URL", "").rstrip("/")
SEARCH_TIMEOUT_SECONDS = float(os.getenv("SEARCH_TIMEOUT_SECONDS", "18"))
SEARCH_RATE_LIMIT_PER_MINUTE = max(
    1, int(os.getenv("SEARCH_RATE_LIMIT_PER_MINUTE", "20"))
)
_RATE_WINDOW_SECONDS = 60.0
_request_timestamps: dict[str, deque[float]] = {}
_rate_lock = asyncio.Lock()


class SearchRequest(BaseModel):
    query: str = Field(min_length=2, max_length=500)
    language: Literal["ar", "en", "auto"] = "auto"
    mode: Literal["hybrid", "web"] = "hybrid"
    limit: int = Field(default=20, ge=1, le=50)


async def enforce_rate_limit(client_key: str) -> None:
    """Limit /search requests per client over a rolling 60-second window."""
    now = time.monotonic()
    cutoff = now - _RATE_WINDOW_SECONDS

    async with _rate_lock:
        timestamps = _request_timestamps.setdefault(client_key, deque())
        while timestamps and timestamps[0] <= cutoff:
            timestamps.popleft()

        if len(timestamps) >= SEARCH_RATE_LIMIT_PER_MINUTE:
            retry_after = max(1, int(_RATE_WINDOW_SECONDS - (now - timestamps[0])))
            raise HTTPException(
                status_code=429,
                detail="تم تجاوز الحد المؤقت لطلبات البحث. حاول بعد قليل.",
                headers={"Retry-After": str(retry_after)},
            )

        timestamps.append(now)

        # Bound memory if many different source addresses reach the service.
        if len(_request_timestamps) > 10_000:
            expired_keys = [
                key
                for key, values in _request_timestamps.items()
                if not values or values[-1] <= cutoff
            ]
            for key in expired_keys:
                _request_timestamps.pop(key, None)


@app.get("/health")
async def health() -> dict[str, str]:
    return {
        "status": "ok",
        "search_provider": "configured" if SEARXNG_BASE_URL else "not_configured",
    }


@app.get("/health/ready")
async def readiness() -> dict[str, object]:
    """Check that the private SearXNG service is reachable from this API."""
    if not SEARXNG_BASE_URL:
        raise HTTPException(status_code=503, detail="Search provider is not configured.")

    try:
        async with httpx.AsyncClient(
            timeout=min(SEARCH_TIMEOUT_SECONDS, 8.0),
            follow_redirects=False,
            headers={"User-Agent": "KnowledgeAtlas-Readiness/0.3"},
        ) as client:
            response = await client.get(f"{SEARXNG_BASE_URL}/healthz")
            response.raise_for_status()
    except httpx.HTTPError as exc:
        raise HTTPException(
            status_code=503, detail="Search provider is not reachable."
        ) from exc

    return {"status": "ready", "search_provider": "reachable", "provider_status": response.status_code}


@app.post("/search")
async def search(
    search_request: SearchRequest,
    request: Request,
) -> dict[str, object]:
    client_key = request.client.host if request.client else "unknown"
    await enforce_rate_limit(client_key)

    if not SEARXNG_BASE_URL:
        raise HTTPException(
            status_code=503, detail="Search provider is not configured."
        )

    expanded = expand_query(search_request.query)
    params = {
        "q": expanded,
        "format": "json",
        "language": search_request.language,
        "safesearch": 1,
    }

    try:
        async with httpx.AsyncClient(
            timeout=SEARCH_TIMEOUT_SECONDS,
            follow_redirects=False,
            headers={"User-Agent": "KnowledgeAtlas-Search/0.2"},
        ) as client:
            response = await client.get(
                f"{SEARXNG_BASE_URL}/search", params=params
            )
            response.raise_for_status()
            payload = response.json()
    except httpx.TimeoutException as exc:
        raise HTTPException(
            status_code=504, detail="Search provider timed out."
        ) from exc
    except (httpx.HTTPError, ValueError) as exc:
        raise HTTPException(
            status_code=502,
            detail="Search provider returned an invalid response.",
        ) from exc

    raw = payload.get("results", []) if isinstance(payload, dict) else []
    if not isinstance(raw, list):
        raw = []

    results = rank_and_shape_results(
        raw, search_request.query, search_request.limit
    )
    retrieved = datetime.now(timezone.utc).isoformat()
    for item in results:
        item["retrievedAt"] = retrieved

    return {
        "query": search_request.query,
        "expandedQuery": expanded,
        "mode": "lexical-meta-search-with-query-expansion",
        "results": results,
        "notice": (
            "تصنيف الناشر مبني على النطاق فقط، ولا يثبت صحة محتوى الصفحة أو "
            "تحكيمها العلمي."
        ),
    }
