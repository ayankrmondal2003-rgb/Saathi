"""Environment Safety Guard for Maitri Test System.
Strictly prevents mutating or destructive test runs against live, production, or Render environments.
"""
import os
import re
from sqlalchemy.engine import make_url

PRODUCTION_HOST_PATTERNS = [
    r"render\.com",
    r"amazonaws\.com",
    r"rds\.amazonaws\.com",
    r"neon\.tech",
    r"supabase\.co",
    r"cockroachlabs\.cloud",
    r"elephantsql\.com",
    r"railway\.app",
    r"fly\.dev",
    r"windows\.net",
    r"azure\.com",
    r"google\.com",
    r"googleapis\.com",
    r"surakshanet-api",
    r"surakshanet_db",
]


class SafetyViolationError(RuntimeError):
    """Raised when an environment or database violates safety boundaries."""
    pass


def assert_safe_test_environment(db_url: str | None = None, redis_url: str | None = None, env_name: str | None = None):
    """Enforces strict isolation rules for the test environment.
    Raises SafetyViolationError if any rule is violated.
    """
    env = (env_name or os.environ.get("ENVIRONMENT") or "").lower().strip()
    if env not in ("test", "testing"):
        raise SafetyViolationError(
            f"MUTATING TEST EXECUTION REJECTED: ENVIRONMENT must be 'test' or 'testing', got '{env}'"
        )

    # 1. Database URL safety check
    target_db = db_url or os.environ.get("TEST_DATABASE_URL") or os.environ.get("DATABASE_URL")
    if not target_db:
        raise SafetyViolationError("MUTATING TEST EXECUTION REJECTED: No database URL provided.")

    parsed_db = make_url(target_db)
    host = (parsed_db.host or "").lower()
    database = (parsed_db.database or "").lower()

    # Reject recognized production hosts
    for pattern in PRODUCTION_HOST_PATTERNS:
        if re.search(pattern, host) or re.search(pattern, target_db.lower()):
            raise SafetyViolationError(
                f"SAFETY VIOLATION: Database host '{host}' matches production pattern '{pattern}'. Mutating tests aborted!"
            )

    # Ensure database name is explicitly test-scoped
    if not (database.endswith("_test") or "test" in database or database.startswith("test_")):
        raise SafetyViolationError(
            f"SAFETY VIOLATION: Database name '{database}' is not explicitly test-scoped (must end in '_test')."
        )

    # Allow localhost / 127.0.0.1 or test socket connections only
    if host not in ("127.0.0.1", "localhost", "::1", "db", ""):
        raise SafetyViolationError(
            f"SAFETY VIOLATION: Remote database host '{host}' rejected for local test execution."
        )

    # 2. Redis URL safety check
    target_redis = redis_url or os.environ.get("TEST_REDIS_URL") or os.environ.get("REDIS_URL")
    if target_redis:
        for pattern in PRODUCTION_HOST_PATTERNS:
            if re.search(pattern, target_redis.lower()):
                raise SafetyViolationError(
                    f"SAFETY VIOLATION: Redis URL '{target_redis}' matches production pattern '{pattern}'."
                )

    # 3. Live emergency broadcast check
    if os.environ.get("PUBLIC_SOS_BROADCAST_ENABLED", "").lower() in ("true", "1", "yes"):
        raise SafetyViolationError("SAFETY VIOLATION: Public SOS broadcasting must not be enabled during tests.")

    return True
