import asyncio
from alembic import context
from app.database.engine import make_engine
from app.config import ConfigurationError, load_settings
from app.database.v1 import metadata


def migrate(connection):
    context.configure(connection=connection, target_metadata=metadata)
    with context.begin_transaction():
        # Serialize schema updates across overlapping container starts on this database.
        print("[startup] PostgreSQL connected; acquiring migration lock", flush=True)
        connection.exec_driver_sql("SET LOCAL lock_timeout = '60s'")
        connection.exec_driver_sql("SELECT pg_advisory_xact_lock(1937076843, 1)")
        if context.get_x_argument(as_dictionary=True).get("ensure_postgis") == "true":
            print("[startup] Ensuring PostGIS extension", flush=True)
            connection.exec_driver_sql("CREATE EXTENSION IF NOT EXISTS postgis")
        print("[startup] Applying Alembic migrations", flush=True)
        context.run_migrations()


async def run():
    print('[startup] Validating migration settings and connecting to PostgreSQL', flush=True)
    engine = make_engine(load_settings().database_url.get_secret_value())
    try:
        async with engine.connect() as connection:
            await connection.run_sync(migrate)
    finally:
        await engine.dispose()


if context.is_offline_mode():
    context.configure(dialect_name="postgresql", target_metadata=metadata, literal_binds=True)
    with context.begin_transaction():
        if context.get_x_argument(as_dictionary=True).get("ensure_postgis") == "true":
            context.execute("CREATE EXTENSION IF NOT EXISTS postgis")
        context.run_migrations()
elif context.config.attributes.get("connection") is not None:
    migrate(context.config.attributes["connection"])
else:
    try:
        asyncio.run(run())
    except ConfigurationError:
        raise
    except Exception as error:
        if context.get_x_argument(as_dictionary=True).get("ensure_postgis") != "true":
            raise
        raise RuntimeError(f"Deployment migration failed ({type(error).__name__}); see last startup stage") from None
