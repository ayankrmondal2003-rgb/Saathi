# Security review — checkpoint 1
Status: PARTIAL.

Changes are confined to welcome UI, branding resources and tests. No authentication, responder authorization, PIN hashing, backend contracts, credentials, database identifiers or public-broadcast settings were changed. Welcome's real button invokes existing navigation; it never creates an incident or enrolls a PIN.

Before new features are enabled:
- Phone identities must use Firebase credentials and backend-validated claims, with explicit account linking and reviewed verified-email policy.
- Volunteer upload is not approval. Enforce reviewer-only decisions, private objects, expiring retrieval, consent, audit and deletion.
- Trip/check-in authorization must be separate from emergency incidents and public links.
- Media requires user initiation and private retention/access enforcement.
- Medical notes must not be disclosed automatically to responders.
- Maps must revoke access with incident grants; sample routes/ETA cannot enter runtime state.
- No new provider, bucket, secret or reviewer authority was configured.

Full service audit and security regression suite still pending for the completed Maitri implementation.
