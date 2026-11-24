# Maitri navigation checkpoint

Inspected the 01–14 Light and Dark SVG boards and the production Compose graph. Both APKs built and installed. The debug JVM suite passed 108 tests. All four MaitriHomeTest instrumentation tests passed on the attached Samsung device.

| Action | Expected destination and Back | Verification this run |
|---|---|---|
| Welcome → Get started | 03 Login, Back to Welcome on first launch | Graph inspection |
| Verified login and profile sync | 02 Home after matching account bind. Sync failure offers retry and sign-out | Graph inspection |
| Home → hold SOS for three seconds | Cancelled hold stays Home. Confirmed shared ACTIVE incident opens 08. Local practice stays on Home | Device hold, cancellation and default consent tests. Live incident flow unexercised |
| Home → Share my trip / Check in | Existing TRIP / CHECK_IN dialogs, dismiss to Home | Callback inspection |
| Home → Manage / Add trusted contact | 05 Circle | Callback inspection |
| Bottom Home / Circle / Responder / Profile | 02 / 05 / responder dashboard / 09. Back from a secondary tab returns Home | Device dock order and Circle callback, production Profile route |
| Responder → application | 04 when application status permits, return to Responder | Graph inspection |
| Inbox or notification → current pending invitation | 06, Back to Responder. Unknown or expired invitation returns to inbox | JVM eligibility test and graph inspection |
| Accept / Decline | Confirmed acceptance opens 07 for the invitation's incident. Confirmed decline returns to Responder | Client and backend response handling inspected |
| Journey → closed or revoked | Stop showing journey and return to Responder | Graph inspection |
| Profile → Edit / Language / Test vibrations / Permissions / Safety PIN | 14 / 10 / 11 / 12 / 13. Back returns to 09 | Edit and Back passed on device. Other callbacks inspected |
| Profile → Themes / Notifications / Privacy & data / Help | Persist Light, Dark or System. Open Android notification settings. Open privacy information and app controls. Show bundled help | Callback inspection |

Removed unreachable destinations from the graph. Automatic approval review declined bulk deletion of legacy screen implementations in CommunityScreens, EntryScreens and IncidentScreens due to possible loss of active code. Those sources remain. Live authentication, incident creation and the two-account responder lifecycle still need end-to-end verification.
