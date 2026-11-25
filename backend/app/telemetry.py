"""Server error reports (Sentry), on only when SENTRY_DSN is set.

Errors only (no performance tracing, to stay inside the free plan). Nothing personal is sent: no request
bodies, headers, cookies, query strings, user objects or local variables, and every message is scrubbed of
coordinates, emails, phone numbers and live-link tokens before it leaves the server.
"""
import logging
import re

log = logging.getLogger("surakshanet")

COORDINATE = re.compile(r"-?\d{1,3}\.\d{4,}")
EMAIL = re.compile(r"[\w.+-]+@[\w-]+\.[\w.]+")
PHONE = re.compile(r"\+?\d[\d ()-]{8,}\d")
LIVE_LINK = re.compile(r"/l/[A-Za-z0-9_-]{20,}")


def scrub_text(text):
    if not isinstance(text, str):
        return text
    text = LIVE_LINK.sub("/l/[token]", text)
    text = EMAIL.sub("[email]", text)
    text = COORDINATE.sub("[coord]", text)
    return PHONE.sub("[phone]", text)


def scrub(event, hint=None):
    """Sentry before_send: keep the error and where it happened, drop anything about the person."""
    request = event.get("request")
    if request:
        event["request"] = {"method": request.get("method"), "url": scrub_text(request.get("url", "").split("?")[0])}
    event.pop("user", None)
    event.pop("extra", None)
    for exc in (event.get("exception") or {}).get("values", []) or []:
        exc["value"] = scrub_text(exc.get("value"))
        for frame in (exc.get("stacktrace") or {}).get("frames", []) or []:
            frame.pop("vars", None)
    if "message" in event:
        event["message"] = scrub_text(event["message"])
    if isinstance(event.get("logentry"), dict):
        event["logentry"]["message"] = scrub_text(event["logentry"].get("message"))
        event["logentry"].pop("params", None)
    for crumb in (event.get("breadcrumbs") or {}).get("values", []) or []:
        crumb["message"] = scrub_text(crumb.get("message"))
        crumb.pop("data", None)
    return event


def init_sentry(settings) -> bool:
    dsn = settings.sentry_dsn.get_secret_value() if settings.sentry_dsn else ""
    if not dsn:
        return False
    import sentry_sdk
    sentry_sdk.init(
        dsn=dsn,
        environment=settings.environment,
        # sentry-sdk 3: collect nothing about the person or the request, and no frame variables.
        data_collection={
            "user_info": False,
            "cookies": {"mode": "off"},
            "http_headers": {"request": {"mode": "off"}},
            "http_bodies": [],
            "url_query_params": {"mode": "off"},
            "database_query_data": False,
            "stack_frame_variables": False,
        },
        max_request_body_size="never",
        traces_sample_rate=0.0,
        before_send=scrub,
        before_breadcrumb=lambda crumb, hint: {**crumb, "message": scrub_text(crumb.get("message")), "data": None},
    )
    log.info("Sentry error reporting on (%s)", settings.environment)
    if getattr(settings, "sentry_test", False):
        sentry_sdk.capture_message("Sentry test from Maitri server (SENTRY_TEST=1)", level="warning")
        sentry_sdk.flush(timeout=5)
        log.info("Sentry test message sent; remove SENTRY_TEST from the environment")
    return True
