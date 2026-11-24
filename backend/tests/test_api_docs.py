"""API_DOCUMENTATION.md lists every route the server has, each with a description (scripts/api_docs.py)."""
import subprocess
import sys
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "api_docs.py"


def test_api_reference_matches_the_routes():
    result = subprocess.run([sys.executable, str(SCRIPT), "--check"], capture_output=True, text=True)
    assert result.returncode == 0, result.stdout + result.stderr
