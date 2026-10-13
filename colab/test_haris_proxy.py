"""Tests for the Colab proxy's client identification and lockout bookkeeping (F5).

Run from the project root:  python -m unittest discover -s colab -p "test_*.py" -v
"""
import sys
import types
import unittest
from pathlib import Path

# The proxy imports aiohttp at module level. These tests only exercise pure functions,
# so a stand-in module lets them run where aiohttp is not installed.
try:
    import aiohttp  # noqa: F401
except ImportError:
    stub = types.ModuleType("aiohttp")
    stub.ClientSession = object
    stub.ClientTimeout = object
    stub.web = types.SimpleNamespace(Request=object, Response=object, StreamResponse=object, Application=object)
    sys.modules["aiohttp"] = stub

sys.path.insert(0, str(Path(__file__).resolve().parent))
import haris_proxy as proxy  # noqa: E402


class ClientOfTest(unittest.TestCase):

    def test_ngrok_takes_the_hop_its_edge_appended(self):
        headers = {"X-Forwarded-For": "203.0.113.9, 198.51.100.7", "CF-Connecting-IP": "203.0.113.9"}
        self.assertEqual(proxy.client_of(headers, "127.0.0.1", "ngrok"), "198.51.100.7")

    def test_ngrok_without_a_forwarding_header_falls_back_to_the_peer(self):
        self.assertEqual(proxy.client_of({}, "127.0.0.1", "ngrok"), "127.0.0.1")

    def test_cloudflare_trusts_only_its_own_header(self):
        headers = {"X-Forwarded-For": "203.0.113.9", "CF-Connecting-IP": "198.51.100.7"}
        self.assertEqual(proxy.client_of(headers, "127.0.0.1", "cloudflare"), "198.51.100.7")

    def test_no_tunnel_ignores_every_forwarding_header(self):
        headers = {"X-Forwarded-For": "203.0.113.9", "CF-Connecting-IP": "203.0.113.9"}
        self.assertEqual(proxy.client_of(headers, "127.0.0.1", "none"), "127.0.0.1")


class FailuresTest(unittest.TestCase):

    def test_counts_recent_failures_per_client(self):
        failures = proxy.Failures(limit=10)
        for _ in range(3):
            failures.add("a", now=100.0)
        self.assertEqual(failures.recent("a", now=101.0), 3)
        self.assertEqual(failures.recent("b", now=101.0), 0)

    def test_old_failures_expire(self):
        failures = proxy.Failures(limit=10)
        failures.add("a", now=100.0)
        self.assertEqual(failures.recent("a", now=100.0 + proxy.LOCKOUT_WINDOW + 1), 0)

    def test_a_full_table_forgets_only_the_least_recent_client(self):
        failures = proxy.Failures(limit=3)
        failures.add("a", now=100.0)
        failures.add("b", now=101.0)
        failures.add("c", now=102.0)
        failures.add("d", now=103.0)
        self.assertEqual(failures.recent("a", now=104.0), 0)
        for client in ("b", "c", "d"):
            self.assertEqual(failures.recent(client, now=104.0), 1, client)


if __name__ == "__main__":
    unittest.main()
