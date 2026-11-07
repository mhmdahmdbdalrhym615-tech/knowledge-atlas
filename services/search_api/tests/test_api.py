import httpx
import pytest
from fastapi.testclient import TestClient

from app import main


def test_health_reports_unconfigured_provider(monkeypatch):
    monkeypatch.setattr(main, "SEARXNG_BASE_URL", "")
    with TestClient(main.app) as client:
        response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok", "search_provider": "not_configured"}


def test_search_rejects_queries_shorter_than_two_characters():
    with TestClient(main.app) as client:
        response = client.post("/search", json={"query": "x"})
    assert response.status_code == 422


def test_search_returns_service_unavailable_when_provider_missing(monkeypatch):
    monkeypatch.setattr(main, "SEARXNG_BASE_URL", "")
    main._request_timestamps.clear()
    with TestClient(main.app) as client:
        response = client.post("/search", json={"query": "soil salinity"})
    assert response.status_code == 503
    assert "not configured" in response.json()["detail"].lower()


def test_search_filters_unsafe_urls_and_deduplicates_results(monkeypatch):
    monkeypatch.setattr(main, "SEARXNG_BASE_URL", "https://searxng.example.test")
    monkeypatch.setattr(main, "SEARCH_RATE_LIMIT_PER_MINUTE", 20)
    main._request_timestamps.clear()
    real_async_client = httpx.AsyncClient

    def handler(request):
        assert request.url.scheme == "https"
        assert request.headers.get("x-real-ip") == "testclient"
        return httpx.Response(200, request=request, json={"results": [
            {"title": "FAO soil resources", "url": "https://www.fao.org/soils", "content": "Soil resources and land management.", "score": 1.0},
            {"title": "Duplicate result", "url": "https://www.fao.org/soils#section", "content": "Duplicate entry.", "score": 0.8},
            {"title": "Plain HTTP result", "url": "http://example.org/resource", "content": "Must be rejected.", "score": 10.0},
        ]})

    def mock_async_client(*args, **kwargs):
        kwargs["transport"] = httpx.MockTransport(handler)
        return real_async_client(*args, **kwargs)

    monkeypatch.setattr(main.httpx, "AsyncClient", mock_async_client)
    with TestClient(main.app) as client:
        response = client.post("/search", json={"query": "soil resources", "limit": 10})
    assert response.status_code == 200
    payload = response.json()
    assert payload["query"] == "soil resources"
    assert len(payload["results"]) == 1
    assert payload["results"][0]["url"] == "https://www.fao.org/soils"
    assert payload["results"][0]["publisherType"] == "منظمة دولية"
    assert payload["results"][0]["retrievedAt"]


def test_search_rate_limit_returns_429_and_retry_after(monkeypatch):
    monkeypatch.setattr(main, "SEARXNG_BASE_URL", "")
    monkeypatch.setattr(main, "SEARCH_RATE_LIMIT_PER_MINUTE", 1)
    main._request_timestamps.clear()
    with TestClient(main.app) as client:
        first = client.post("/search", json={"query": "soil salinity"})
        second = client.post("/search", json={"query": "soil salinity"})
    assert first.status_code == 503
    assert second.status_code == 429
    assert int(second.headers["Retry-After"]) > 0


def test_readiness_checks_private_search_provider(monkeypatch):
    monkeypatch.setattr(main, "SEARXNG_BASE_URL", "http://searxng.internal")
    real_async_client = httpx.AsyncClient

    def handler(request):
        assert request.url.path == "/healthz"
        return httpx.Response(200, request=request, text="ok")

    def mock_async_client(*args, **kwargs):
        kwargs["transport"] = httpx.MockTransport(handler)
        return real_async_client(*args, **kwargs)

    monkeypatch.setattr(main.httpx, "AsyncClient", mock_async_client)
    with TestClient(main.app) as client:
        response = client.get("/health/ready")

    assert response.status_code == 200
    assert response.json() == {
        "status": "ready",
        "search_provider": "reachable",
        "provider_status": 200,
    }


def test_readiness_reports_unreachable_provider(monkeypatch):
    monkeypatch.setattr(main, "SEARXNG_BASE_URL", "http://searxng.internal")

    real_async_client = httpx.AsyncClient

    def handler(request):
        return httpx.Response(503, request=request)

    def mock_async_client(*args, **kwargs):
        kwargs["transport"] = httpx.MockTransport(handler)
        return real_async_client(*args, **kwargs)

    monkeypatch.setattr(main.httpx, "AsyncClient", mock_async_client)
    with TestClient(main.app) as client:
        response = client.get("/health/ready")

    assert response.status_code == 503
    assert response.json()["detail"] == "Search provider is not reachable."
