"""Read-only live-location page for people without the app: /l/<token>.

The token is an HMAC of the incident id with LIVE_LINK_SECRET, so nothing is stored and it can't be guessed.
It works only while the incident is ACTIVE: when the SOS ends (or is auto-expired) the page says so and the
location endpoint returns no coordinates. The page shows no name, phone number or email, only the last
location, its accuracy and age, and a Call 112 button.
"""
import base64
import hashlib
import hmac
import html
import logging
import secrets
from uuid import UUID

from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import HTMLResponse, JSONResponse
from sqlalchemy import text

router = APIRouter()
log = logging.getLogger("surakshanet.live")

MAPLIBRE = "https://unpkg.com/maplibre-gl@5.24.1/dist/"
JS_SRI = "sha384-nXMl9YiLoSG5KBPTFgvxVuIqQQcqIvo0bFrvoEaGJC6r7IibIzF1S36Zbf/sKBtL"
CSS_SRI = "sha384-uTttxo/aOKbdE5RlD/SPzSDoDmNvGlUYPjONi2MN/b7c9HPSvW07OIuyP7uL6jxK"
STYLE = "https://tiles.openfreemap.org/styles/liberty"


def link_key(app) -> bytes:
    key = getattr(app.state, "live_link_key", None)
    if key is None:
        secret = app.state.settings.live_link_secret
        if secret is not None:
            key = hashlib.sha256(b"maitri-live-link:" + secret.get_secret_value().encode()).digest()
        else:
            # Links then stop working after a restart; set LIVE_LINK_SECRET in Render to keep them valid.
            log.warning("LIVE_LINK_SECRET not set; live-location links are valid until the next restart only")
            key = secrets.token_bytes(32)
        app.state.live_link_key = key
    return key


def make_token(key: bytes, incident_id: UUID) -> str:
    mac = hmac.new(key, b"live:" + incident_id.bytes, hashlib.sha256).digest()[:16]
    return base64.urlsafe_b64encode(incident_id.bytes + mac).rstrip(b"=").decode()


def read_token(key: bytes, token: str) -> UUID | None:
    try:
        raw = base64.urlsafe_b64decode(token + "=" * (-len(token) % 4))
    except (ValueError, TypeError):
        return None
    if len(raw) != 32:
        return None
    incident_id = UUID(bytes=raw[:16])
    expected = hmac.new(key, b"live:" + incident_id.bytes, hashlib.sha256).digest()[:16]
    return incident_id if hmac.compare_digest(expected, raw[16:]) else None


def public_link(request: Request, incident_id: UUID) -> str:
    base = request.app.state.settings.public_url or str(request.base_url)
    return base.rstrip("/") + "/l/" + make_token(link_key(request.app), incident_id)


PRIVATE = {"Cache-Control": "no-store", "X-Robots-Tag": "noindex, nofollow", "Referrer-Policy": "no-referrer",
           "X-Content-Type-Options": "nosniff"}


@router.get("/l/{token}/location")
async def live_location(token: str, request: Request):
    incident_id = read_token(link_key(request.app), token)
    if incident_id is None:
        raise HTTPException(404, "Link not found")
    async with request.app.state.sessions() as db:
        row = (await db.execute(text("""SELECT status, ST_Y(latest_location::geometry) AS latitude,
            ST_X(latest_location::geometry) AS longitude, location_accuracy_meters, location_recorded_at
            FROM incidents WHERE id=:id"""), {"id": incident_id})).mappings().first()
    if row is None:
        raise HTTPException(404, "Link not found")
    if row["status"] != "ACTIVE":
        return JSONResponse({"status": "ENDED"}, headers=PRIVATE)
    body = {"status": "ACTIVE", "latitude": row["latitude"], "longitude": row["longitude"],
            "accuracy_meters": row["location_accuracy_meters"],
            "recorded_at": row["location_recorded_at"].isoformat() if row["location_recorded_at"] else None}
    return JSONResponse(body, headers=PRIVATE)


@router.get("/l/{token}", response_class=HTMLResponse)
async def live_page(token: str, request: Request):
    if read_token(link_key(request.app), token) is None:
        return HTMLResponse(PAGE.replace("{{BODY}}", "<p class=lead>This link isn't valid.</p>"), status_code=404, headers=PRIVATE)
    page = PAGE.replace("{{BODY}}", BODY).replace("{{TOKEN}}", html.escape(token))
    csp = ("default-src 'none'; script-src 'self' https://unpkg.com 'unsafe-inline'; style-src https://unpkg.com 'unsafe-inline'; "
           "img-src data: blob: https://tiles.openfreemap.org; connect-src 'self' https://tiles.openfreemap.org; "
           "worker-src blob:; font-src https://tiles.openfreemap.org; base-uri 'none'; form-action 'none'")
    return HTMLResponse(page, headers={**PRIVATE, "Content-Security-Policy": csp})


PAGE = """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="robots" content="noindex,nofollow"><title>Maitri · Live location</title>
<link rel="stylesheet" href=\"""" + MAPLIBRE + """maplibre-gl.css" integrity=\"""" + CSS_SRI + """" crossorigin="anonymous">
<style>
:root{color-scheme:light dark;--bg:#FFF7F9;--ink:#40303A;--muted:#7D6771;--accent:#B82055;--card:#FCE8ED}
@media (prefers-color-scheme:dark){:root{--bg:#1E171E;--ink:#FAEFF5;--muted:#C3ADBA;--accent:#FF9CBE;--card:#36232F}}
body{margin:0;font-family:system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;background:var(--bg);color:var(--ink)}
main{max-width:640px;margin:0 auto;padding:20px 16px 32px}
.eyebrow{color:var(--accent);font-size:11px;letter-spacing:.12em;font-weight:600;margin:0}
h1{font-size:24px;margin:6px 0 6px} .lead{color:var(--muted);margin:0 0 16px;line-height:1.45}
#map{height:56vh;min-height:280px;border-radius:20px;overflow:hidden;background:var(--card)}
.status{margin:12px 2px;color:var(--muted);font-size:14px}
.actions{display:flex;gap:10px;flex-wrap:wrap;margin-top:8px}
a.btn{flex:1;min-width:140px;text-align:center;text-decoration:none;font-weight:600;padding:14px 16px;border-radius:999px}
a.call{background:var(--accent);color:#fff} a.maps{background:var(--card);color:var(--accent)}
.ended{background:var(--card);padding:16px;border-radius:16px}
footer{margin-top:20px;font-size:12px;color:var(--muted)}
</style></head><body><main>
<p class=eyebrow>MAITRI SOS</p>{{BODY}}
<footer>Shared through Maitri. This page shows no name or contact details and stops working when the SOS ends.</footer>
</main></body></html>"""

BODY = """<h1>Live location</h1>
<p class=lead>Someone in your circle started an SOS and shared their location with you. If they may be in danger, call 112.</p>
<div id=map role=img aria-label="Map with the shared location"></div>
<p class=status id=status aria-live=polite>Loading the latest location…</p>
<div class=actions><a class="btn call" href="tel:112">Call 112</a><a class="btn maps" id=maps href="#" hidden>Open in Maps</a></div>
<script src=\"""" + MAPLIBRE + """maplibre-gl.js" integrity=\"""" + JS_SRI + """" crossorigin="anonymous"></script>
<script>
const token = "{{TOKEN}}";
let map, marker;
function ago(iso){ if(!iso) return ""; const s=Math.max(0,(Date.now()-Date.parse(iso))/1000);
  return s<60?"just now":s<3600?Math.round(s/60)+" min ago":Math.round(s/3600)+" h ago"; }
function circle(lon,lat,r){ const pts=[],dl=r/111320,dn=r/(111320*Math.cos(lat*Math.PI/180));
  for(let i=0;i<=64;i++){const a=2*Math.PI*i/64; pts.push([lon+dn*Math.cos(a),lat+dl*Math.sin(a)]);}
  return {type:"Feature",geometry:{type:"Polygon",coordinates:[pts]}}; }
async function refresh(){
  let d; try { const r = await fetch(token + "/location", {cache:"no-store"}); d = await r.json(); }
  catch(e){ document.getElementById("status").textContent = "Can't reach Maitri right now. Retrying…"; return; }
  if (d.status !== "ACTIVE") {
    document.querySelector("main").innerHTML = '<p class=eyebrow>MAITRI SOS</p><h1>This SOS has ended</h1>' +
      '<p class="lead ended">The location is no longer shared.</p>';
    clearInterval(timer); return;
  }
  if (d.latitude == null) { document.getElementById("status").textContent = "Waiting for their first location…"; return; }
  const at = [d.longitude, d.latitude], acc = d.accuracy_meters || 0;
  if (!map) {
    map = new maplibregl.Map({container:"map", style:"%STYLE%", center:at, zoom: acc>1000?12:acc>200?14:16, attributionControl:{compact:false}});
    map.on("load", () => { map.addSource("acc",{type:"geojson",data:circle(at[0],at[1],acc)});
      map.addLayer({id:"acc",type:"fill",source:"acc",paint:{"fill-color":"#B82055","fill-opacity":0.15}}); });
    marker = new maplibregl.Marker({color:"#D92B65"}).setLngLat(at).addTo(map);
  } else { marker.setLngLat(at); const s = map.getSource("acc"); if (s) s.setData(circle(at[0],at[1],acc)); }
  document.getElementById("status").textContent = "Updated " + ago(d.recorded_at) + (acc ? " · accurate to about " + Math.round(acc) + " m" : "");
  const maps = document.getElementById("maps"); maps.hidden = false;
  maps.href = "https://www.google.com/maps/search/?api=1&query=" + d.latitude + "," + d.longitude;
}
refresh(); const timer = setInterval(refresh, 15000);
</script>""".replace("%STYLE%", STYLE)
