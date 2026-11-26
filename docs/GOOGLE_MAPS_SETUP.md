# Google Maps key for the Maitri app (zero cost)

The app draws every map with the **Maps SDK for Android** through Maps Compose. Map loads from the Android SDK
("Mobile Native Dynamic Maps") are billed at $0 with no monthly cap. Nothing else from Google Maps Platform is used:

- no Map ID and no cloud-based map styling (that is billed as Dynamic Maps, which has a free cap); the light and dark
  looks are bundled JSON styles (`app/src/main/res/raw/maitri_map_light.json`, `maitri_map_dark.json`);
- no Routes, Directions, Distance Matrix, Geocoding, Places, Static Maps or Street View calls (distance and walking time
  are straight-line estimates labelled "approx."; "Open in Google Maps" hands off to the Google Maps app);
- the `/l/<token>` live-location web page keeps OpenFreeMap + Leaflet (no key, no Google web maps).

Prices and free tiers change. Check https://mapsplatform.google.com/pricing/ before release (**Unverified** here).

## 0. Decide which Cloud project holds the key

Google requires a billing account on the project before the Maps SDK works, even though Android map loads cost $0.

**Billing on `surakshanet-c4ec1` (the Firebase project) moves Firebase from the free Spark plan to Blaze
(pay as you go).** On Blaze, anything with a paid tier can then charge, most importantly **phone-number sign-in SMS
codes** (Maitri has that path; on Spark it cannot send SMS at all), plus any Firestore / Storage / Functions use above
the free quota.

- **Recommended: a separate project only for Maps** (for example `maitri-maps`). Firebase stays on Spark; the key works
  the same way. Use this unless you specifically want one project.
- Same project (`surakshanet-c4ec1`), as originally asked: follow the same steps, and also turn off the paid paths
  you don't use (step 6).

## 1. Project and the one API

1. https://console.cloud.google.com → project picker (top bar) → choose `surakshanet-c4ec1`, or **New project** →
   name `maitri-maps` → **Create**, then select it.
2. ☰ → **APIs & Services → Library** → search **Maps SDK for Android** → **Enable**.
3. Enable nothing else from Maps Platform. Check ☰ → **APIs & Services → Enabled APIs & services**: there must be no
   Routes, Directions, Distance Matrix, Geocoding, Geolocation, Places, Maps Static, Street View, Maps JavaScript or
   Map Tiles API. If any is there, open it → **Disable API**.

## 2. Billing account

1. ☰ → **Billing** → **Link a billing account** (or **Manage billing accounts → Create account**).
2. Country India, account type Individual, add a card (Google may place a small, refunded verification charge).
3. Link it to the project chosen in step 0.

## 3. The API key, restricted

1. ☰ → **APIs & Services → Credentials → + Create credentials → API key**. Copy it; click **Edit API key**.
2. Name: `maitri-android-maps`.
3. **Application restrictions → Android apps → + Add**, once per line:

   | Package name | SHA-1 certificate fingerprint | What it signs |
   |---|---|---|
   | `in.maitri.app` | `D4:5A:84:1B:32:9F:1F:4C:27:BD:45:58:AA:3B:61:F3:32:97:ED:AE` | debug builds (`~/.android/debug.keystore`) |
   | `in.maitri.app` | `89:8A:9D:B1:ED:29:AE:4D:E7:C9:E9:4C:A1:88:4E:0A:C2:B2:5D:3A` | upload key (release, tester and `-PsignDebugWithUpload` builds) |
   | `in.maitri.app` | *Play app signing SHA-1* | only once Maitri is on Google Play: Play Console → Test and release → App integrity → App signing key certificate |

   (`./gradlew :app:signingReport` prints the first two again.)
4. **API restrictions → Restrict key** → tick only **Maps SDK for Android** → **Save**.
5. Changes take up to 5 minutes.

## 4. Put the key in the build (never in git)

In `local.properties` (git-ignored) add:

```
maitriMapsApiKey=AIza...your key...
```

`app/build.gradle.kts` passes it to the manifest through `manifestPlaceholders` (`com.google.android.geo.API_KEY`).
Without it the app still builds; maps show the "Map unavailable" card with **Open in Google Maps**.

## 5. Budget alert at ₹100

1. ☰ → **Billing → Budgets & alerts → Create budget**.
2. Name `maitri-maps-₹100`; Scope: the project from step 0, all services.
3. Amount: Specified amount **₹100**; untick "Include credits in cost" only if you want alerts before credits are used.
4. Alert thresholds: 50 %, 90 %, 100 % of actual spend (and 100 % forecasted); email to billing admins → **Finish**.

A budget only **emails**; it does not stop charges by itself.

## 6. Quota caps (so a leaked key can't cost anything)

1. ☰ → **APIs & Services → Enabled APIs & services → Maps SDK for Android → Quotas & system limits**.
2. Android map loads are $0, so a cap protects against abuse rather than cost. Set **Map loads per day** (if listed) to a
   number well above real use, e.g. 20,000 (edit pencil → new value → **Submit**).
3. Every other Maps Platform API stays **disabled** (step 1.3), and the key is restricted to the Maps SDK and Maitri's
   package + certificates (step 3), so it can't be used for anything billable.
4. Same project only: Firebase console → **Authentication → Sign-in method → Phone** → disable it if you don't use
   phone sign-in, or add an **SMS region policy** (Authentication → Settings → SMS region policy → Allow only India)
   and keep an eye on the budget alert.

Optional hard stop: a budget can publish to Pub/Sub and a small Cloud Function can detach billing when the budget is
hit ("Disable billing usage with notifications" in the Cloud Billing docs). That needs Cloud Functions on the same
billing account and would also stop the Maps SDK, so Maitri doesn't set it up by default.

## 7. Check it worked

- Install a debug build, open the Responder tab: tiles appear in light and dark mode.
- `adb logcat | grep -i "Google Maps Android API"`: no `Authorization failure` line.
- Cloud console → **APIs & Services → Maps SDK for Android → Metrics**: requests show, cost stays ₹0.
