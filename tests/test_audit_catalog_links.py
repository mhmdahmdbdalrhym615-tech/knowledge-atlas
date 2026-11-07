import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / "tools" / "audit_catalog_links.py"
SPEC = importlib.util.spec_from_file_location("audit_catalog_links", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class CatalogueAuditTests(unittest.TestCase):
    def test_current_catalog_has_120_unique_https_records(self):
        payload = json.loads(MODULE.DEFAULT_CATALOG.read_text(encoding="utf-8"))
        records = payload["records"]
        MODULE.validate_catalog(records)
        self.assertEqual(120, len(records))
        self.assertEqual(120, len({row["id"] for row in records}))

    def test_rejects_http_and_credential_urls(self):
        base = {
            "id": "x", "nameAr": "مصدر", "nameEn": "Source",
            "officialUrl": "https://example.org",
        }
        with self.assertRaises(ValueError):
            MODULE.validate_catalog([{**base, "officialUrl": "http://example.org"}])
        with self.assertRaises(ValueError):
            MODULE.validate_catalog([{**base, "officialUrl": "https://user:pass@example.org"}])

    def test_http_error_is_not_reported_as_verified_or_reachable(self):
        from urllib.error import HTTPError
        row = {"id": "x", "nameAr": "مصدر", "nameEn": "Source",
               "categoryId": 1, "officialUrl": "https://example.org"}
        error = HTTPError(row["officialUrl"], 404, "not found", {}, None)
        with patch.object(MODULE, "urlopen", side_effect=error):
            result = MODULE.probe(row, "2026-11-07T00:00:00+00:00")
        self.assertFalse(result["reachable"])
        self.assertEqual("HTTP_ERROR_UNVERIFIED", result["auditStatus"])
        self.assertIn("unverified", result["auditStatus"].lower())

    def test_success_only_means_reachable_not_verified(self):
        row = {"id": "x", "nameAr": "مصدر", "nameEn": "Source",
               "categoryId": 1, "officialUrl": "https://example.org"}
        class Response:
            status = 200
            def __enter__(self): return self
            def __exit__(self, *args): return False
            def geturl(self): return "https://example.org/"
            def read(self, limit): return b"ok"
        with patch.object(MODULE, "urlopen", return_value=Response()):
            result = MODULE.probe(row, "2026-11-07T00:00:00+00:00")
        self.assertTrue(result["reachable"])
        self.assertEqual("REACHABLE_UNVERIFIED", result["auditStatus"])


if __name__ == "__main__":
    unittest.main()
