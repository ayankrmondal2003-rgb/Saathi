"""Volunteer review panel at /admin, for Firebase accounts with the custom claim {"admin": true}.

The page is a small static shell: the reviewer signs in with Google (Firebase JS SDK, pinned with SRI), and the
page calls the JSON API below with that ID token. Every API call checks the token and the admin claim on the
server, so the page itself holds nothing secret. Documents open through presigned R2 links valid for 5 minutes,
and every opening and decision is written to admin_audit_log.

Grant the claim once with backend/scripts/set_admin_claim.py.
"""
import html
import json
from typing import Literal
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import HTMLResponse
from pydantic import Field
from sqlalchemy import text

from app.core import Identity, database, identity
from app.files import bucket_for
from app.inbox import add_item
from app.schemas import Input

router = APIRouter()
DOC_NAMES = {"GOVT_ID": "Government issued identity proof", "ADDRESS_PROOF": "Address proof",
             "POLICE_CERT": "Police character certificate", "PHOTO": "Recent photograph",
             "DECLARATION": "Signed volunteer declaration"}
REVIEW_URL_SECONDS = 300


async def admin(who: Identity = Depends(identity)) -> Identity:
    if not who.admin:
        raise HTTPException(403, {"code": "ADMIN_REQUIRED", "message": "This account isn't a Maitri reviewer"})
    return who


async def audit(db, who: Identity, action: str, application_id=None, file_id=None, decision=None, note=None):
    await db.execute(text("""INSERT INTO admin_audit_log(admin_uid, action, application_id, file_id, decision, note)
        VALUES (:uid, :action, :app, :file, :decision, :note)"""),
        {"uid": who.uid, "action": action, "app": application_id, "file": file_id, "decision": decision, "note": note})


class Decision(Input):
    decision: Literal["APPROVED", "REJECTED", "REQUIRES_CORRECTION"]
    note: str = Field(default="", max_length=1000)


@router.get("/api/admin/applications")
async def applications(status: Literal["PENDING", "APPROVED", "REJECTED", "ALL"] = "PENDING",
                       who=Depends(admin), db=Depends(database)):
    where = {"PENDING": "a.status IN ('SUBMITTED','UNDER_REVIEW')", "APPROVED": "a.status = 'APPROVED'",
             "REJECTED": "a.status IN ('REJECTED','REQUIRES_CORRECTION')", "ALL": "a.status <> 'NOT_STARTED'"}[status]
    rows = (await db.execute(text(f"""SELECT a.id, a.status, a.submitted_at, a.reviewed_at, u.display_name, u.city
        FROM volunteer_applications a JOIN users u ON u.id = a.user_id WHERE {where}
        ORDER BY a.submitted_at NULLS LAST, a.created_at LIMIT 200"""))).mappings().all()
    return [dict(r) for r in rows]


@router.get("/api/admin/applications/{application_id}")
async def application(application_id: UUID, who=Depends(admin), db=Depends(database)):
    row = (await db.execute(text("""SELECT a.*, u.display_name, u.email, u.phone_number, u.city
        FROM volunteer_applications a JOIN users u ON u.id = a.user_id WHERE a.id = :id"""),
        {"id": application_id})).mappings().first()
    if row is None:
        raise HTTPException(404, "Application not found")
    if row["status"] == "SUBMITTED":
        await db.execute(text("UPDATE volunteer_applications SET status='UNDER_REVIEW', updated_at=now() WHERE id=:id"),
                         {"id": application_id})
    await audit(db, who, "APPLICATION_OPENED", application_id)
    docs = (await db.execute(text("""SELECT id, doc_type, mime_type, size_bytes, uploaded_at FROM stored_files
        WHERE owner_user_id = :uid AND kind = 'VOLUNTEER_DOC' AND status = 'UPLOADED' ORDER BY doc_type, uploaded_at DESC"""),
        {"uid": row["user_id"]})).mappings().all()
    return {"id": row["id"], "status": "UNDER_REVIEW" if row["status"] == "SUBMITTED" else row["status"],
            "name": row["display_name"], "email": row["email"], "phone": row["phone_number"], "city": row["city"],
            "submitted_at": row["submitted_at"], "reviewer_notes": row["reviewer_notes"],
            "consent_declared": row["consent_declared"],
            "documents": [{**dict(d), "label": DOC_NAMES[d["doc_type"]]} for d in docs]}


@router.post("/api/admin/applications/{application_id}/documents/{file_id}/url")
async def document_url(application_id: UUID, file_id: UUID, request: Request, who=Depends(admin), db=Depends(database)):
    bucket = bucket_for(request)
    row = (await db.execute(text("""SELECT f.object_key, f.mime_type FROM stored_files f
        JOIN volunteer_applications a ON a.user_id = f.owner_user_id
        WHERE f.id = :file AND a.id = :app AND f.kind = 'VOLUNTEER_DOC' AND f.status = 'UPLOADED'"""),
        {"file": file_id, "app": application_id})).mappings().first()
    if row is None:
        raise HTTPException(404, "Document not found")
    await audit(db, who, "DOCUMENT_VIEWED", application_id, file_id)
    return {"url": bucket.url("GET", row["object_key"], REVIEW_URL_SECONDS), "mime_type": row["mime_type"],
            "expires_in": REVIEW_URL_SECONDS}


@router.post("/api/admin/applications/{application_id}/decision")
async def decide(application_id: UUID, body: Decision, who=Depends(admin), db=Depends(database)):
    if body.decision != "APPROVED" and not body.note.strip():
        raise HTTPException(422, {"code": "NOTE_REQUIRED", "message": "Tell the applicant why, so they can fix it"})
    row = (await db.execute(text("""UPDATE volunteer_applications SET status=:status, reviewer_notes=:note,
        reviewed_at=now(), updated_at=now() WHERE id=:id AND status IN ('SUBMITTED','UNDER_REVIEW','APPROVED')
        RETURNING user_id"""), {"status": body.decision, "note": body.note.strip() or None, "id": application_id})).first()
    if row is None:
        raise HTTPException(409, {"code": "NOT_REVIEWABLE", "message": "Only submitted applications can be decided"})
    if body.decision != "APPROVED":
        # A volunteer who is no longer approved stops receiving requests at once.
        await db.execute(text("UPDATE users SET responder_available=false WHERE id=:id"), {"id": row[0]})
    await audit(db, who, "APPLICATION_DECIDED", application_id, decision=body.decision, note=body.note.strip() or None)
    title, message = {
        "APPROVED": ("You're approved as a Maitri volunteer", "Turn on \"Available for nearby requests\" on the Responder tab when you can help."),
        "REJECTED": ("Your volunteer application wasn't approved", body.note.strip()),
        "REQUIRES_CORRECTION": ("Your volunteer application needs changes", body.note.strip()),
    }[body.decision]
    await add_item(db, row[0], "VOLUNTEER_DECISION", title, message, data={"decision": body.decision})
    return {"status": body.decision}


@router.get("/admin", response_class=HTMLResponse)
async def admin_page(request: Request):
    settings = request.app.state.settings
    config = {"apiKey": settings.firebase_web_api_key or "",
              "authDomain": settings.firebase_auth_domain or f"{settings.firebase_project_id}.firebaseapp.com",
              "projectId": settings.firebase_project_id}
    csp = ("default-src 'none'; script-src 'self' 'unsafe-inline' https://www.gstatic.com https://apis.google.com; "
           "style-src 'unsafe-inline'; img-src data: https:; connect-src 'self' https://*.googleapis.com; "
           f"frame-src https://{html.escape(config['authDomain'])} https://accounts.google.com; base-uri 'none'; form-action 'none'")
    page = PAGE.replace("%CONFIG%", json.dumps(config)).replace("%READY%", "true" if config["apiKey"] else "false")
    return HTMLResponse(page, headers={"Cache-Control": "no-store", "X-Robots-Tag": "noindex, nofollow",
                                       "Referrer-Policy": "no-referrer", "X-Frame-Options": "DENY",
                                       "Content-Security-Policy": csp})


FIREBASE = "https://www.gstatic.com/firebasejs/13.4.0/"
PAGE = """<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="robots" content="noindex,nofollow"><title>Maitri · Volunteer review</title>
<style>
:root{color-scheme:light dark;--bg:#FFF7F9;--ink:#40303A;--muted:#7D6771;--accent:#B82055;--card:#FCE8ED;--surface:#fff}
@media (prefers-color-scheme:dark){:root{--bg:#1E171E;--ink:#FAEFF5;--muted:#C3ADBA;--accent:#FF9CBE;--card:#36232F;--surface:#28212A}}
body{margin:0;font-family:system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;background:var(--bg);color:var(--ink)}
main{max-width:960px;margin:0 auto;padding:24px 16px}
.eyebrow{color:var(--accent);font-size:11px;letter-spacing:.12em;font-weight:600;margin:0} h1{margin:4px 0 16px}
button{font:inherit;border:0;border-radius:999px;padding:10px 16px;cursor:pointer;background:var(--card);color:var(--accent);font-weight:600}
button.primary{background:var(--accent);color:#fff} button:disabled{opacity:.5;cursor:default}
.grid{display:grid;grid-template-columns:minmax(240px,1fr) 2fr;gap:16px} @media (max-width:720px){.grid{grid-template-columns:1fr}}
.card{background:var(--surface);border-radius:18px;padding:16px} .muted{color:var(--muted)}
.item{padding:10px;border-radius:12px;cursor:pointer} .item:hover,.item.on{background:var(--card)}
textarea{width:100%;min-height:80px;border-radius:12px;border:1px solid var(--card);padding:10px;font:inherit;background:var(--bg);color:var(--ink);box-sizing:border-box}
.row{display:flex;gap:8px;flex-wrap:wrap;align-items:center} .tabs button{padding:6px 12px}
</style></head><body><main>
<p class=eyebrow>MAITRI</p><h1>Volunteer review</h1>
<div id=auth class=row></div><p id=msg class=muted role=status></p>
<div id=app hidden>
 <div class="row tabs" style="margin-bottom:12px"><button data-s=PENDING>Pending</button><button data-s=APPROVED>Approved</button><button data-s=REJECTED>Rejected</button><button data-s=ALL>All</button></div>
 <div class=grid><div class=card id=list></div><div class=card id=detail><p class=muted>Choose an application.</p></div></div>
</div>
<script src=\"""" + FIREBASE + """firebase-app-compat.js" integrity="sha384-vUeQRrZnYEGSJG28QTRheyEPzsOvJZ7eZtcwAXRYwqdXxbds3zzelpA5IkfJst4T" crossorigin="anonymous"></script>
<script src=\"""" + FIREBASE + """firebase-auth-compat.js" integrity="sha384-8vGoPNF0eSBQDVwYPTtpMBKGLPlwt3i4Fcm0S2n/yBS9Hm/K73f8BEbq5QVm8LGu" crossorigin="anonymous"></script>
<script>
const READY = %READY%, msg = t => document.getElementById("msg").textContent = t || "";
const esc = s => String(s ?? "").replace(/[&<>"']/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
let current = null, status = "PENDING";
if (!READY) msg("FIREBASE_WEB_API_KEY isn't set on the server, so sign-in can't start.");
else {
  firebase.initializeApp(%CONFIG%);
  const auth = firebase.auth(), box = document.getElementById("auth");
  auth.onAuthStateChanged(async user => {
    box.innerHTML = user ? `<span class=muted>${esc(user.email)}</span> <button id=out>Sign out</button>` : `<button class=primary id=in>Sign in with Google</button>`;
    if (user) { document.getElementById("out").onclick = () => auth.signOut(); await load(); }
    else { document.getElementById("in").onclick = () => auth.signInWithPopup(new firebase.auth.GoogleAuthProvider()).catch(e => msg(e.message));
           document.getElementById("app").hidden = true; }
  });
  async function api(path, opts = {}) {
    const token = await auth.currentUser.getIdToken();
    const r = await fetch(path, {...opts, headers: {"Authorization": "Bearer " + token, "Content-Type": "application/json"}});
    const body = r.status === 204 ? null : await r.json().catch(() => null);
    if (!r.ok) throw new Error(body?.detail?.message || body?.detail || ("Error " + r.status));
    return body;
  }
  async function load() {
    try {
      const rows = await api("/api/admin/applications?status=" + status);
      document.getElementById("app").hidden = false; msg("");
      document.getElementById("list").innerHTML = rows.length ? rows.map(r =>
        `<div class=item data-id="${esc(r.id)}"><b>${esc(r.display_name || "No name")}</b><br><span class=muted>${esc(r.status)} · ${esc(r.city || "")} ${r.submitted_at ? "· " + new Date(r.submitted_at).toLocaleString() : ""}</span></div>`).join("")
        : "<p class=muted>Nothing here.</p>";
      document.querySelectorAll("#list .item").forEach(el => el.onclick = () => open(el.dataset.id));
    } catch (e) { msg(e.message); }
  }
  async function open(id) {
    try {
      const a = current = await api("/api/admin/applications/" + id);
      document.getElementById("detail").innerHTML = `<h2 style="margin-top:0">${esc(a.name)}</h2>
        <p class=muted>${esc(a.email)} · ${esc(a.phone || "no phone")} · ${esc(a.city || "no city")}<br>Status: <b>${esc(a.status)}</b> · consent ${a.consent_declared ? "given" : "missing"}</p>
        <h3>Documents (${a.documents.length} of 5)</h3>${a.documents.map(d => `<div class=row style="margin:6px 0"><span>${esc(d.label)}</span>
          <button data-f="${esc(d.id)}">Open (5 min link)</button></div>`).join("") || "<p class=muted>No uploaded documents.</p>"}
        <h3>Decision</h3><textarea id=note placeholder="Note to the applicant (required to reject or ask for changes)">${esc(a.reviewer_notes || "")}</textarea>
        <div class=row style="margin-top:8px"><button class=primary data-d=APPROVED>Approve</button><button data-d=REQUIRES_CORRECTION>Ask for changes</button><button data-d=REJECTED>Reject</button></div>`;
      document.querySelectorAll("#detail [data-f]").forEach(b => b.onclick = async () => {
        try { const u = await api(`/api/admin/applications/${a.id}/documents/${b.dataset.f}/url`, {method: "POST"});
              window.open(u.url, "_blank", "noopener,noreferrer"); } catch (e) { msg(e.message); } });
      document.querySelectorAll("#detail [data-d]").forEach(b => b.onclick = async () => {
        if (!confirm("Record this decision? The applicant is notified.")) return;
        try { await api(`/api/admin/applications/${a.id}/decision`, {method: "POST",
                body: JSON.stringify({decision: b.dataset.d, note: document.getElementById("note").value})});
              msg("Decision saved."); await load(); await open(a.id); } catch (e) { msg(e.message); } });
    } catch (e) { msg(e.message); }
  }
  document.querySelectorAll(".tabs button").forEach(b => b.onclick = () => { status = b.dataset.s; load(); });
}
</script></main></body></html>"""
