# Controlled demo operations

## Start or stop the exercise

Before admitting phones, check the web service Events, database expiry and your private export, Key Value Connections and the active allowlist. Use /health for process liveness and /ready for PostGIS plus the configured Redis subscriber and ping. A successful probe does not test Firebase send permissions. Do a self-test on each installation.

**Emergency stop:** suspend the Render web service to stop API requests, WebSockets and the in-process senders. Messages already accepted by Firebase can still arrive. Set TEST_ACCOUNT_UIDS to an empty JSON array before resuming. Close or review old test incidents before restoring accounts; queued invitations can otherwise become eligible again. Allowlist changes take effect on process restart. Stop all instances when immediate cutoff matters.

## Observe

| Signal | Where to look | Action |
|---|---|---|
| API / database | Render health events, web metrics and Postgres connections | Investigate sustained 503s, pool saturation and deploy events. Check the internal database binding and migration revision. |
| Redis | Key Value connections, memory, Redis reconnect warnings and /ready | Verify internal hostname/authentication after rotations. Reopen the incident and fetch current state after reconnect. Pub/Sub cannot replay missed events. |
| WebSockets | Record opened connections on B and C, heartbeat acknowledgments and last fix time | Compare both clients after movement and closure. Redis connection count is a service metric shared by many clients; there is no fleet-wide WebSocket subscriber gauge in this build. |
| GPS and closure | Android last server acknowledgment and redacted route/status warnings in web logs | Check precise permission, fix age/accuracy, upload connectivity and PIN cooldown. Verify closure by fetching the incident again. |
| Worker / FCM | Search web service logs for outbox errors; inspect aggregate statuses below | Investigate growing pending jobs, repeated retries, permanent failures and stale claims. Compare Firebase acceptance with physical receipt. |

Run these aggregate checks from an authorized PostgreSQL client connected to the demo database. Free web services lack dashboard shell access. Avoid selecting raw tokens, ticket URLs, PIN material or coordinate histories.

```sql
SELECT delivery_status, count(*) FROM notification_outbox GROUP BY delivery_status;
SELECT count(*) AS stale_claims FROM notification_outbox
 WHERE delivery_status = 'SENDING' AND last_attempt_at < now() - interval '90 seconds';
SELECT count(*) AS stale_active_fixes FROM incidents WHERE status = 'ACTIVE'
 AND (location_recorded_at IS NULL OR location_recorded_at < now() - interval '120 seconds');
SELECT version_num FROM alembic_version;
SHOW transaction_isolation;
```

The stale-fix query uses the default freshness threshold. The API process polls approximately every two seconds. Row claims use SKIP LOCKED, retries use backoff, and interrupted claims become reclaimable. An interruption after a provider accepts a send but before the database records it can repeat delivery. Track acceptance and device display separately.

## Recover

- **Service restart:** open the web service's Manual Deploy menu and restart the service. Check /ready, sign in, register the device and repeat one self-test. A restart also restarts the in-process notification poller.
- **Rollback:** select a previous successful deploy under Events and use Rollback after confirming its schema compatibility. A code rollback leaves the database schema at its current revision. Free Postgres has no managed backups. For database recovery from your own private export, restore to a separate test instance, validate PostGIS and the migration head, then deliberately switch the private database binding while the app is suspended.
- **Database unavailable:** check service status and connection limits before changing credentials. Verify region and private host, then run the read-only checks above. Review failed migration logs. Avoid rerunning a migration against an unknown target.
- **Credential rotation:** create a replacement key for the dedicated Firebase account, replace the Render secret file, redeploy and verify a profile request plus a single-device test send. Disable and delete the old key after confirming the replacement.
- **Retention:** after reviewing the target and recovery point, run `python -m app.maintenance` from an authorized local backend environment connected to the intended demo database. This mutates eligible rows in bounded batches. Repeat under an approved schedule for a persistent demo. No retention scheduler is provisioned by this Blueprint. Check the provider's backup retention separately from the live-table policy.
