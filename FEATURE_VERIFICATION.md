# Phase 17 verification

| Scope | Status | Evidence |
|---|---|---|
| Backend regression and spatial authorization | VERIFIED | Two independent private PostGIS 3.7 clusters, migration 0007, 49 tests passed each time |
| Distributed tracking | PARTIAL | Cross-worker updates, revocation and closure passed with two app instances sharing real Redis 7. Redis outage recovery and hosted process testing remain open |
| Android Firebase configuration | VERIFIED | Client project surakshanet-c4ec1, package com.example.surakshanet, debug build succeeded |
| Samsung package installation | VERIFIED | ADB streamed install returned Success and Android accepted the launch intent |
| Android verified-session profile sync and retry | IMPLEMENTED BUT UNTESTED | Requires a deployed backend and verified allowlisted identity |
| FCM ownership and state | PARTIAL | Automated backend coverage, no observed real token registration, acceptance, receipt, display or open |
| Contacts and PIN | PARTIAL | Automated suite only. Native picker, multiple numbers, SMS composer and scrolling need device rehearsal |
| Samsung background GPS | BLOCKED | Requires a controlled authenticated incident. Lock, disconnect, reconnect, check fix age, close and confirm service stops |
| Render | IMPLEMENTED BUT UNTESTED | Blueprint and migration hook prepared. Owner must supply Firebase secret and deploy |
| Demo readiness | PARTIAL | Isolated backend walkthrough available. Three-device live pipeline awaits rehearsal |

The live rehearsal passes only when each responder sees coordinates after acceptance, new coordinates after movement, and loses access after closure. Record observed timestamps without storing coordinates, tokens, phone numbers or passwords.
