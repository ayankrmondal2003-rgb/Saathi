# Three-phone controlled rehearsal

**Execution status — pending.** Record the APK version, Android version, OEM, channel settings, battery mode and redacted incident identifier. Keep account credentials, PINs, tickets, tokens and precise real-world locations out of the evidence log.

## Setup

- [ ] Use one isolated development Firebase project, its Android client configuration, an HTTPS test API and a dedicated migrated PostGIS database. Set TEST_ACCOUNT_UIDS to a JSON array containing exactly the three verified Firebase UIDs. Restart the API with this configuration. An empty list denies access. Keep Firebase Admin credentials exclusively on the backend.
- [ ] Install the same debug APK configured with surakshaDevApiUrl on three physical phones. Assign A as victim, B and C as responders. Register and verify each test account. Check that an unrelated account is denied access to the API.
- [ ] On A, configure a contact and enroll a primary PIN. Confirm status after activity recreation. Grant location permission on all phones. Allow notifications and check the urgent channel, sound and vibration on B and C.
- [ ] Use the same controlled location source type on all phones. For an equatorial synthetic track, use longitude offsets in metres divided by 111319.49 and latitude zero. Keep fix timestamps current and reported accuracy under 100 m. Start A at 0 m, B at 400 m and C at 2400 m. On B and C explicitly opt in and enable foreground location sharing on the responder dashboard. Check for a server-accepted fix from each.

## Run sheet

| Step | Action | Evidence required |
|---|---|---|
| 1 | A selects test-server sharing and activates SOS | One active test incident, A's tracking notification and increasing GPS timestamps |
| 2 | Keep A stationary | B gets one invitation. C has none. Record outbox acceptance separately from handset receipt |
| 3 | Observe B before tapping | Record actual sound, vibration and lock-screen behavior. The configured waveform spans 5.2 seconds |
| 4 | B taps notification, then accepts | Backend shows accepted membership, B sees a current coordinate, A's accepted count increases |
| 5 | Move A to 1900 m, refresh C's fix | C receives one invitation. B, now 1500 m away, retains access |
| 6 | C accepts | Both phones have independent authorized sessions, A sees two acceptances |
| 7 | Move A to 3000 m | Both phones receive the new fix in order. Open the map on a test device and verify its target |
| 8 | Temporarily disconnect B, move A again, reconnect B | B indicates degraded freshness and obtains the current snapshot. Record recovery time |
| 9 | Press Home on A, then lock it and advance its synthetic position | Record fixes observed remotely while backgrounded and locked, then repeat under battery saver |
| 10 | A submits an incorrect PIN | API rejects it. Incident and valid grants remain active |
| 11 | A submits its correct PIN | One closure timestamp, outstanding invitations invalidated, grants revoked and A's service notification disappears |
| 12 | Keep both responder screens open, then reopen them | Neither shows precise location. A fresh ticket, snapshot, precise-location read and post-closure upload are refused |
| 13 | Repeat with C initially outside a stationary incident and moving inward | C is invited only after submitting an eligible fresh fix. Test decline and expiry in separate runs |
| 14 | Repeat using denied/approximate permission, disabled GPS, rotation, offline closure, and a restarted backend | Record actual UI, last accepted fix and authoritative server state. Verify that offline closure never displays a server confirmation |

For each step record PASS, FAIL or BLOCKED, timestamp, and redacted evidence from A, B, C and the server. An FCM message ID is transport acceptance. Only backend acceptance records a responder acknowledgement. A PIN confirms the closure request. Human safety requires independent confirmation.

Force-stop or shutdown interrupts location collection. A START_NOT_STICKY service requires an explicit restart. Offline devices cannot upload or confirm closure until connected. User channel settings, Do Not Disturb, battery restrictions and OEM policy can change handset behavior. End the rehearsal by resolving simulated incidents, disabling responder availability and removing test-device registrations.
