# Knowledge Atlas Search API

This is the first server-side search adapter for the Android client.

## Features
- Provider credentials remain on the server, not in the APK.
- Arabic normalization and a small domain-specific query-expansion dictionary.
- SearXNG metasearch in JSON mode.
- HTTPS-only result URLs, deduplication, lexical reranking and conservative publisher-domain labels.
- Publisher labels explicitly do not claim that page content is fact-checked.

## Limitations
This is not neural semantic search. It does not create embeddings, use a vector database, crawl full pages, or verify individual claims. Query expansion is a recall aid and may misinterpret ambiguous queries. A later milestone should add evaluated multilingual embeddings and provenance-aware indexing.

## Configure
Set SEARXNG_BASE_URL on the server to a SearXNG instance with JSON output enabled:
- python -m pip install -r requirements.txt
- export SEARXNG_BASE_URL="https://your-searxng-host"
- uvicorn app.main:app --host 0.0.0.0 --port 8000

Public deployment must use HTTPS, request rate limiting, monitoring and restricted administrative access. Do not expose an unprotected SearXNG instance.

Endpoints: GET /health and POST /search.


## Docker Compose (self-hosted)

1. Copy `.env.example` to `.env` and generate a unique random `SEARXNG_SECRET`.
2. Review enabled SearXNG engines and their terms, then run `docker compose up -d --build`.
3. The Atlas API is bound to `127.0.0.1:8000` by default; place it behind an HTTPS reverse proxy before remote use.
4. Keep `.env` private and rotate the secret if it is exposed. Do not commit it.

The Compose stack uses Valkey through its Redis-compatible protocol. SearXNG receives the secret via its container environment; no secret is stored in the tracked settings file.
