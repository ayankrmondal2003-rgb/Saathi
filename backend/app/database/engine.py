"""One place that turns DATABASE_URL into an engine, for the API, Alembic and maintenance.

Neon (and most hosted Postgres) hands out libpq-style URLs: `?sslmode=require&channel_binding=require`.
asyncpg rejects both keywords, so they are translated here. Neon's pooled endpoint (`-pooler` in the host)
runs PgBouncer in transaction mode, where asyncpg's prepared-statement cache breaks; it is turned off there.
"""
from uuid import uuid4

from sqlalchemy.engine import URL, make_url
from sqlalchemy.ext.asyncio import create_async_engine

# libpq sslmode values that mean "use TLS"; asyncpg takes them through its own `ssl` argument.
_TLS_MODES = {"require", "verify-ca", "verify-full", "prefer", "allow"}


def normalize_url(url: URL) -> URL:
    query = dict(url.query)
    mode = query.pop("sslmode", None)
    query.pop("channel_binding", None)  # libpq-only; asyncpg negotiates SCRAM itself
    if mode in _TLS_MODES and "ssl" not in query:
        query["ssl"] = mode if mode != "allow" else "prefer"
    elif mode == "disable" and "ssl" not in query:
        query["ssl"] = "disable"
    return url.set(query=query)


def is_pooled(url: URL) -> bool:
    return bool(url.host) and "-pooler" in url.host


def make_engine(database_url: str, **options):
    url = normalize_url(make_url(database_url))
    if is_pooled(url):
        connect_args = dict(options.pop("connect_args", {}))
        connect_args.update(statement_cache_size=0,
                            prepared_statement_name_func=lambda: f"__asyncpg_{uuid4()}__")
        options["connect_args"] = connect_args
    return create_async_engine(url, hide_parameters=True, **options)
