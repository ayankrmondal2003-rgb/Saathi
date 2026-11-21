"""Verify migrations and pytest on a fresh private PostGIS cluster using PostgreSQL binaries on PATH."""
import os
from pathlib import Path
import secrets
import socket
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    pgsql_bin = ROOT / ".venv" / "pgsql" / "bin"
    current_path = os.environ.get("PATH", "")
    if pgsql_bin.exists() and str(pgsql_bin) not in current_path:
        os.environ["PATH"] = str(pgsql_bin) + os.pathsep + current_path

    with tempfile.TemporaryDirectory(prefix="suraksha_phase5_") as folder:
        data = Path(folder) / "database"
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            port = sock.getsockname()[1]
        password = secrets.token_hex(24)
        env = dict(os.environ, PGHOST="127.0.0.1", PGPORT=str(port),
                   PGUSER="test_admin", PGPASSWORD=password)

        def run(*args, **kwargs):
            try:
                return subprocess.run(args, cwd=ROOT, env=env, check=True, text=True, **kwargs)
            except subprocess.CalledProcessError as err:
                if hasattr(err, "stderr") and err.stderr:
                    print(f"Subprocess stderr:\n{err.stderr}", file=sys.stderr)
                raise

        secret = Path(folder) / "bootstrap-password"
        secret.write_text(password, encoding="ascii")
        try:
            run("initdb", "-D", data, "-U", "test_admin", "-A", "scram-sha-256", f"--pwfile={secret}",
                "--encoding=UTF8", "--locale=C")
        finally:
            secret.unlink()
        run("pg_ctl", "-D", data, "-l", Path(folder) / "server.log", "-o", f"-h 127.0.0.1 -p {port}", "-w", "start")
        try:
            name = "surakshanet_test"
            run("createdb", name)

            def sql(statement):
                return run("psql", "-XAt", "-v", "ON_ERROR_STOP=1", "-d", name, "-c", statement, capture_output=True).stdout.strip()

            assert sql("SELECT current_database()") == name
            sql("CREATE EXTENSION postgis")
            print("Isolated database:", name, "PostGIS:", sql("SELECT postgis_lib_version()"), flush=True)
            url = f"postgresql+asyncpg://test_admin:{password}@127.0.0.1:{port}/{name}"
            env.update(TEST_DATABASE_URL=url, DATABASE_URL=url, FIREBASE_PROJECT_ID="isolated-test", ENVIRONMENT="test")
            run(sys.executable, "-m", "alembic", "upgrade", "head")
            print("Revision:", sql("SELECT version_num FROM alembic_version"), flush=True)
            extra_args = sys.argv[1:] if len(sys.argv) > 1 else ["-q", "--tb=short"]
            run(sys.executable, "-m", "pytest", *extra_args)
        finally:
            run("pg_ctl", "-D", data, "-m", "fast", "-w", "stop")


if __name__ == "__main__":
    main()
