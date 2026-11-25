"""Generates ../API_DOCUMENTATION.md from the app's real routes, so the reference can't drift from the code.

    cd backend && .venv\\Scripts\\python.exe scripts\\api_docs.py          # rewrite the file
    cd backend && .venv\\Scripts\\python.exe scripts\\api_docs.py --check  # exit 1 if it is out of date

tests/test_api_docs.py runs the check with the backend suite.
"""
import inspect
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from fastapi.routing import APIRoute, APIWebSocketRoute  # noqa: E402

from app.config import Settings  # noqa: E402
from app.main import create_app  # noqa: E402

OUT = Path(__file__).resolve().parents[2] / "API_DOCUMENTATION.md"

HEADER = """# Maitri API reference

The endpoint table below is generated from the server code by `backend/scripts/api_docs.py`. Don't edit it by hand:
`backend/tests/test_api_docs.py` fails when it no longer matches the routes.

## Basics

- **Base URL:** the Render service, for example `https://surakshanet-api.onrender.com`. The app reads it from
  `maitriProdApiUrl` (release) or `maitriDevApiUrl` (debug) in `local.properties`.
- **Sign-in:** every `/api/...` call except the public ones in the table needs `Authorization: Bearer <Firebase ID
  token>`. The server verifies the token with Firebase Admin and finds or creates the account. Accounts must be
  active; in `ENVIRONMENT=beta` any verified account may use the service, otherwise only `TEST_ACCOUNT_UIDS`.
- **Admin:** `/api/admin/...` additionally needs the Firebase custom claim `{"admin": true}`
  (`backend/scripts/set_admin_claim.py`). The `/admin` page itself is public HTML that signs in with Firebase.
- **Idempotency:** `POST /api/incidents` and `POST /api/circle-sharing/sessions` take an `Idempotency-Key` header
  (a UUID). Repeating a request with the same key returns the original result instead of creating a second one.
- **Errors:** JSON `{"detail": ...}`. Where the app needs to react to a specific case, `detail` is
  `{"code": "...", "message": "..."}`, for example `SOS_RATE_LIMITED` (429), `STORAGE_NOT_CONFIGURED` (503),
  `MEDICAL_NOTES_UNAVAILABLE` (503), `INVITE_NOT_FOUND` (404). Messages are safe to show; they never contain
  other people's data. Rate limits answer 429.
- **Real time:** `POST /api/incidents/{id}/ws-ticket` returns a short-lived ticket for the `/ws/...` WebSocket, which
  streams incident updates. Pushes go through Firebase Cloud Messaging as data messages.
- **Live links:** `/l/{token}` is a public page with a person's live location during their SOS; the token is an
  HMAC (LIVE_LINK_SECRET) and stops working when the SOS ends.
- **Files:** uploads go straight to private Cloudflare R2 storage with a presigned URL from
  `POST /api/files/upload-url`, then `POST /api/files/{id}/complete`. Downloads use short-lived presigned URLs.
  SOS photos and voice notes are visible to the sender and to the circle members who got that SOS's alert;
  volunteer responders never see them.

## Endpoints

Access: **public** (no sign-in), **account** (Firebase ID token), **admin** (token with the admin claim).
"""


# For endpoints without a docstring. scripts/api_docs.py --check fails if any route has neither.
DESCRIPTIONS = {
    ("GET", "/admin"): "Volunteer review page (HTML). Signs in with Firebase; its API calls need the admin claim.",
    ("GET", "/api/admin/applications"): "Volunteer applications waiting for review, newest first.",
    ("GET", "/api/admin/applications/{application_id}"): "One application with its documents' metadata.",
    ("POST", "/api/admin/applications/{application_id}/decision"): "Approve or reject an application, with a note to the applicant.",
    ("POST", "/api/admin/applications/{application_id}/documents/{file_id}/url"): "Short-lived download link for one document of the application. Logged.",
    ("POST", "/api/circle/invites"): "Creates an 8-character invite code for one trusted contact (valid 7 days).",
    ("POST", "/api/circle/invites/accept"): "The contact enters the code: links their account to that trusted contact. Rate limited.",
    ("GET", "/api/circle/requests"): "Circle requests waiting for this account's answer (made by phone-number match).",
    ("POST", "/api/circle/requests/{request_id}/{decision}"): "Accept or decline a circle request (`decision` is accept or decline).",
    ("POST", "/api/circle/memberships/{link_id}/add-back"): "Add the owner of a circle you're in to your own circle: saves them as your contact (their verified number, or the one you give) and sends them a circle request. Mutual only once they accept.",
    ("GET", "/api/circle-sharing/links"): "This account's linked contacts (people who receive its alerts in the app).",
    ("POST", "/api/circle-sharing/links"): "Links a contact using a pairing code.",
    ("DELETE", "/api/circle-sharing/links/{link_id}"): "Removes a link; that person stops receiving alerts and shared locations.",
    ("POST", "/api/circle-sharing/pairing-code"): "Creates a short-lived pairing code for a contact.",
    ("GET", "/api/circle-sharing/requests/{key}"): "Looks up a trip or check-in by the Idempotency-Key it was created with (retry after a lost reply).",
    ("GET", "/api/circle-sharing/sessions"): "Trips and check-ins shared with this account or started by it.",
    ("POST", "/api/circle-sharing/sessions"): "Starts a shared trip or a timed check-in for chosen contacts. Needs Idempotency-Key.",
    ("GET", "/api/circle-sharing/sessions/{session_id}"): "A trip or check-in: status, due time and, while active, the latest location.",
    ("PUT", "/api/circle-sharing/sessions/{session_id}/location"): "The owner's latest location for an active trip.",
    ("POST", "/api/circle-sharing/sessions/{session_id}/opened"): "A recipient opened the shared trip or check-in (shown to the owner).",
    ("POST", "/api/circle-sharing/sessions/{session_id}/stop"): "Ends a trip, or checks in safely.",
    ("GET", "/api/files/profile-photo"): "Download link for this account's profile photo, if one is set.",
    ("POST", "/api/files/profile-photo"): "Makes an uploaded image the profile photo (the previous one is deleted).",
    ("POST", "/api/files/upload-url"): "Presigned upload to private storage for a volunteer document, SOS photo or voice note, or profile photo.",
    ("DELETE", "/api/files/{file_id}"): "Deletes one of this account's files from storage.",
    ("POST", "/api/files/{file_id}/complete"): "Confirms an upload: the server checks the stored object's size before the file is usable.",
    ("GET", "/api/files/{file_id}/download-url"): "Short-lived download link: own files, or SOS media from an SOS this account was alerted to.",
    ("GET", "/api/inbox"): "Inbox items: circle SOS alerts, SOS ended, circle requests, check-in reminders and missed check-ins.",
    ("POST", "/api/inbox/read-all"): "Marks every inbox item read.",
    ("POST", "/api/inbox/{item_id}/read"): "Marks one inbox item read.",
    ("GET", "/l/{token}"): "Public live-location page for an active SOS (map, last update). Stops working when the SOS ends.",
    ("GET", "/l/{token}/location"): "The live-location page's data: latest position and time, while the SOS is active.",
    ("GET", "/health"): "Liveness: the process is up.",
    ("GET", "/api/users/me/medical-notes"): "The account's medical notes (stored encrypted; shown to an accepted responder during an SOS).",
    ("PUT", "/api/users/me/medical-notes"): "Saves medical notes (encrypted with MEDICAL_NOTES_KEY; 503 if the key isn't set).",
    ("POST", "/api/devices/register"): "Registers this phone's push token for the account.",
    ("POST", "/api/devices/{device_id}/test-alert"): "Sends a test push to one of the account's phones.",
    ("DELETE", "/api/devices/{token}"): "Removes a push token (sign-out).",
    ("POST", "/api/incidents"): "Starts an SOS with the first location. Needs Idempotency-Key; at most SOS_PER_HOUR real SOS per hour.",
    ("POST", "/api/incidents/{incident_id}/invitations/{invitation_id}/revoke"): "The owner removes a responder who accepted.",
    ("GET", "/api/incidents/{incident_id}/location"): "Latest location of the person in need, for the owner and authorized responders.",
    ("POST", "/api/incidents/{incident_id}/location"): "The owner's new location fix during the SOS.",
    ("POST", "/api/incidents/{incident_id}/request-help"): "An authorized responder asks for more nearby responders (\"Bring in help\").",
    ("POST", "/api/incidents/{incident_id}/resolve"): "Ends the SOS with the safety PIN. A duress PIN looks the same but escalates.",
    ("PUT", "/api/incidents/{incident_id}/responders/status"): "A responder's status: on the way, helping, can't help.",
    ("GET", "/api/incidents/{incident_id}/snapshot"): "Full incident state for the owner: responders with names, distance and status; location; circle delivery.",
    ("GET", "/api/incidents/{incident_id}/trusted-contacts"): "Delivery status of the SOS for each trusted contact.",
    ("POST", "/api/incidents/{incident_id}/trusted-contacts/alert"): "Alerts trusted contacts: pushes to linked ones, returns the others for an SMS from the phone.",
    ("POST", "/api/incidents/{incident_id}/ws-ticket"): "One-time ticket for the incident WebSocket.",
    ("GET", "/api/responders/invitations"): "Pending SOS requests for this volunteer.",
    ("POST", "/api/responders/invitations/{invitation_id}/response"): "Accept or decline an SOS request.",
    ("PUT", "/api/responders/location"): "A volunteer's current location, used to find nearby responders.",
    ("POST", "/api/security/safety-pin/change"): "Changes the safety and duress PINs (needs the current PIN).",
    ("POST", "/api/security/safety-pin/recover"): "Resets the PINs after a fresh sign-in.",
    ("POST", "/api/security/safety-pin/setup"): "Sets the safety PIN (and optional duress PIN) for the first time.",
    ("GET", "/api/security/safety-pin/status"): "Whether PINs are set, and any lockout.",
    ("GET", "/api/trusted-contacts"): "The account's trusted contacts.",
    ("POST", "/api/trusted-contacts"): "Adds a trusted contact.",
    ("DELETE", "/api/trusted-contacts/{contact_id}"): "Removes a trusted contact (and its circle link).",
    ("PUT", "/api/trusted-contacts/{contact_id}"): "Edits a trusted contact, including whether they get SOS alerts.",
    ("POST", "/api/trusted-contacts/{contact_id}/primary"): "Makes a contact the primary one.",
    ("GET", "/api/users/me"): "The signed-in account: profile, responder settings, volunteer status.",
    ("PATCH", "/api/users/me"): "Updates part of the profile.",
    ("PUT", "/api/users/me"): "Updates the profile and responder availability.",
    ("WS", "/api/ws/incidents/{incident_id}"): "Live incident updates (same as /ws/incidents/...; kept for older app builds).",
    ("WS", "/ws/incidents/{incident_id}"): "Live incident updates for the owner and authorized responders. Needs a ws-ticket.",
    ("POST", "/api/volunteer/consent"): "Records consent to document verification and the volunteer declaration.",
    ("POST", "/api/volunteer/intent"): "\"Yes, I'd like to help\" or \"Not now\" on the volunteer screen.",
    ("GET", "/api/volunteer/status"): "Volunteer application status and which documents are uploaded.",
    ("POST", "/api/volunteer/submit"): "Submits the application for review (all five documents and consent required).",
}


def access(route):
    names = set()

    def walk(dep, root=False):
        call = getattr(dep, "call", None)
        if call is not None and not root:
            names.add(getattr(call, "__name__", ""))
        for d in dep.dependencies:
            walk(d)
    walk(route.dependant, root=True)
    if any("admin" in n for n in names):
        return "admin"
    if names & {"account", "identity", "current_user", "verified_identity"}:
        return "account"
    return "public"


def summary(endpoint):
    doc = inspect.getdoc(endpoint) or ""
    first = doc.strip().split("\n\n")[0].replace("\n", " ").strip()
    return first.replace("|", "/")


def routes():
    app = create_app(Settings(_env_file=None, database_url="postgresql+asyncpg://docs:docs@localhost/docs",
                              firebase_project_id="docs", environment="test"), initialize_firebase=False)
    found = []

    def visit(items):
        for r in items:
            if hasattr(r, "original_router"):
                visit(r.original_router.routes)
            elif isinstance(r, APIRoute):
                for method in sorted(r.methods - {"HEAD"}):
                    text = summary(r.endpoint) or DESCRIPTIONS.get((method, r.path), "")
                    found.append((r.endpoint.__module__, r.path, method, access(r), text))
            elif isinstance(r, APIWebSocketRoute):
                text = summary(r.endpoint) or DESCRIPTIONS.get(("WS", r.path), "")
                found.append((r.endpoint.__module__, r.path, "WS", "ticket", text))
    visit(app.routes)
    missing = [f"{m} {p}" for _, p, m, _, t in found if not t]
    if missing:
        sys.exit("No description for: " + ", ".join(missing) + " (add a docstring or an entry in DESCRIPTIONS)")
    return sorted(set(found), key=lambda x: (x[0], x[1], x[2]))


def render():
    lines = [HEADER]
    module = None
    for mod, path, method, who, text in routes():
        if mod != module:
            module = mod
            lines += ["", f"### `{mod}`", "", "| Method | Path | Access | What it does |", "|---|---|---|---|"]
        lines.append(f"| {method} | `{path}` | {who} | {text} |")
    return "\n".join(lines) + "\n"


if __name__ == "__main__":
    text = render()
    if "--check" in sys.argv:
        current = OUT.read_text(encoding="utf8") if OUT.exists() else ""
        if current.replace("\r\n", "\n") != text:
            sys.exit("API_DOCUMENTATION.md is out of date: run backend/scripts/api_docs.py")
        print("API_DOCUMENTATION.md is up to date")
    else:
        OUT.write_text(text, encoding="utf8", newline="\n")
        print("wrote", OUT, len(text.splitlines()), "lines")
