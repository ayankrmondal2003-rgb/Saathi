# Free Render deployment for the controlled demo

> **Superseded by [DEPLOYMENT.md](DEPLOYMENT.md).** The free stack now uses Neon for the database, Cloudflare R2 for files, no Redis, and a cron-job.org keep-alive. This page describes the earlier Render Postgres + Key Value layout and is kept for reference.

## Before pushing or syncing

1. Open the existing **surakshanet_db** in Render. Check its plan, workspace, region, creation date, and expiry. For a zero-cost rehearsal it must already be Free and remain active through the demo. The Blueprint creates only a Free web service and Free Key Value. It cannot change an existing database bill.
2. Both region entries in render.yaml currently specify Singapore. If the database is elsewhere, change both entries to its region before committing. The web service can make outbound private connections to same-region datastores.
3. Check the workspace Billing page, included build minutes, bandwidth and free instance hours. Keep usage within included allowances. If you require a hard zero-cost boundary, use a free workspace without a payment method and review all existing resources. Render can charge overages when a payment method is present.
4. For an existing linked Blueprint, turn off automatic Blueprint sync and service auto-deploy before pushing if you want a manual review. Review existing resource plans first. Paid Key Value instances cannot be downgraded; if one already exists, plan a separate Free instance and review existing charges before proceeding. Free Key Value is limited to one per workspace. [Key Value](https://render.com/docs/key-value)
5. Review migrations through 0007 before first startup. For data worth keeping, make a private logical export with pg_dump and verify it independently. If existing application tables lack the matching Alembic revision, reconcile the schema before starting.

## Render Dashboard

1. Select **New → Blueprint**, choose **ayankrmondal2003-rgb/Saathi**, branch **master**, and root **render.yaml**. For an existing managed Blueprint, review its pending sync instead. Verify the preview shows Free for both services and no database creation. Stop if it requests paid resources.
2. Privately enter the existing database's **Internal Database URL** as DATABASE_URL. Set TEST_ACCOUNT_UIDS to `[]` during provisioning. For an existing service edit these values under **Environment**. REDIS_URL comes from the Blueprint's internal service reference.
3. When ready, manually apply. Under web service **Environment → Secret Files**, add firebase-admin.json with the private credential for surakshanet-c4ec1. The backend reads /etc/secrets/firebase-admin.json. Follow [Firebase setup](FIREBASE_ADMIN_SETUP.md) to add only the three verified UIDs and redeploy. Keep Key Value external access disabled. Review internal authentication under its Connections settings and resync the private binding if changed.
4. In **Events / Logs**, confirm the revision starts successfully. Open the actual web service HTTPS origin plus /ready; expect HTTP 200 with status ready. Configure surakshaDevApiUrl only with that origin. Follow [cloud checks](CLOUD_TEST_GUIDE.md) for authenticated testing.

## Startup and recovery

The backend Docker build context contains start-render.sh and render-migrate.sh at /app. The image default and Blueprint both launch `sh /app/start-render.sh`. In the web service Settings, check Docker Command for a stale override. During each container start the launcher runs render-migrate.sh before executing one Uvicorn worker. In Logs, use the last `[startup]` stage to distinguish configuration, PostgreSQL connection, migration lock, PostGIS, Alembic and Firebase initialization failures. If no startup marker appears, compare the deployed commit, image and effective Docker Command in Events and Settings. Use /ready to check the configured Redis listener and database after startup. Alembic takes a transaction-scoped PostgreSQL advisory lock before extension setup, version checks and upgrades. Competing migrators against the same database wait for the lock. A 60-second lock timeout or migration error exits startup before the API can bind. PostgreSQL releases the lock on transaction end or disconnect. After a failure, inspect logs and database availability, permissions and revision before retrying. Redis remains configured for tickets and events. Firebase verification, allowlisting and incident-specific grants continue to apply.

## Availability and data lifetime

- The web service sleeps after 15 idle minutes. Waking it can take about a minute. Open /ready shortly before a rehearsal. Geofence evaluation and notification polling pause while the process sleeps. Invitations can expire before a phone receives them.
- Free Postgres expires **30 days after creation** and has **no managed backups**. Export any required data before expiry. The service filesystem is ephemeral.
- Free Key Value keeps data only in memory. Restarts erase tickets and interrupt subscriptions. Clients must reconnect, obtain a fresh ticket and reload authorized state from PostgreSQL.
- The single low-CPU worker shares 512 MB with the polling tasks. Restarts, contention or quota exhaustion can interrupt the demonstration. Free web services lack dashboard shell access. Use synthetic locations and controlled test accounts throughout. [Render free-tier limits](https://render.com/docs/free) · [Blueprint specification](https://render.com/docs/blueprint-spec)
