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
