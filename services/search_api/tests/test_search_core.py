from app.search_core import canonical_https_url, classify_publisher, expand_query, normalize_query, rank_and_shape_results

def test_arabic_normalization():
    assert normalize_query("إِصْلاحُ التُّرْبَة") == "اصلاح التربه"

def test_query_expansion_preserves_question_and_adds_soil_terms():
    expanded=expand_query("كيف أعالج ملوحة التربة؟")
    assert "كيف أعالج ملوحة التربة؟" in expanded
    assert "soil salinity" in expanded and "electrical conductivity" in expanded

def test_publisher_classification_is_only_a_domain_hint():
    assert classify_publisher("www.fao.org")[0] == "منظمة دولية"
    assert classify_publisher("unknown-example.com")[0] == "غير مصنف"

def test_only_https_urls_without_credentials_are_accepted():
    assert canonical_https_url("https://example.org/a#part") == "https://example.org/a"
    assert canonical_https_url("http://example.org/a") is None
    assert canonical_https_url("https://user:pass@example.org/a") is None

def test_ranking_deduplicates_and_rejects_http():
    raw=[
      {"title":"Soil salinity crop productivity","url":"https://www.fao.org/soil","content":"soil salinity crop productivity","score":1.0},
      {"title":"duplicate","url":"https://www.fao.org/soil#part","content":"duplicate","score":0.8},
      {"title":"Blog","url":"https://unknown-example.com/post","content":"soil crop","score":0.9},
      {"title":"unsafe","url":"http://example.org","content":"soil","score":9.0},
    ]
    results=rank_and_shape_results(raw,"soil salinity crop",20)
    assert len(results)==2
    assert results[0]["publisherType"]=="منظمة دولية"
    assert all(x["url"].startswith("https://") for x in results)

from pathlib import Path
import json

def test_offline_catalog_has_at_least_100_entries_and_all_12_categories():
    catalog_path = Path(__file__).resolve().parents[3] / "app" / "src" / "main" / "assets" / "knowledge_catalog.json"
    catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
    records = catalog["records"]
    assert len(records) >= 100
    assert {record["categoryId"] for record in records} == set(range(1, 13))
    assert len({record["id"] for record in records}) == len(records)

def test_catalog_uses_https_and_does_not_fake_verification():
    catalog_path = Path(__file__).resolve().parents[3] / "app" / "src" / "main" / "assets" / "knowledge_catalog.json"
    records = json.loads(catalog_path.read_text(encoding="utf-8"))["records"]
    assert all(record["officialUrl"].startswith("https://") for record in records)
    assert all(record["lastVerifiedDate"] == "" for record in records)
    assert all(record["confidenceLevel"] == "REVIEW_REQUIRED" for record in records)
    assert all(record["accessType"] in {"FREE", "PAID", "MIXED", "REGISTRATION", "UNKNOWN"} for record in records)
