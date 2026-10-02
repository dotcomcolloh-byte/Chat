# Deploy the backend on Render

## Build and start

Use the repository's `backend` directory as the Docker build context and `backend/Dockerfile` as the Dockerfile. The image builds the `telefam-backend-all.jar`, installs FFmpeg, and listens on Render's injected `PORT` value.

## Database schema initialization

On startup, `DatabaseFactory.init()` connects to PostgreSQL and, before starting the Ktor listener, calls Exposed `SchemaUtils.createMissingTablesAndColumns()` for the project's registered table objects, creates the additional indexes, and applies `src/main/resources/db/rls_policies.sql`. This initializes the schema on a fresh database; use the Render Postgres internal URL from a service in the same region. `DATABASE_URL` must be in JDBC form (`jdbc:postgresql://HOST:5432/DB`), with `DATABASE_USER` and `DATABASE_PASSWORD` configured separately.

The current startup path performs both schema changes and runtime database work with the same database account. The RLS SQL comments describe a separate restricted runtime role, but this project does not yet separate migration privileges from runtime privileges. Do not switch the service to a restricted role until table creation, ownership, and grants have been deliberately split and tested.

## Required environment variables

Set secrets in Render's Environment page; do not commit `.env` or send provider credentials in chat. `AppConfig` currently requires:

- Database: `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`
- Auth: `JWT_ACCESS_SECRET`, `JWT_REFRESH_SECRET`, `GOOGLE_CLIENT_ID_ANDROID`, `GOOGLE_CLIENT_ID_WEB`, `APPLE_CLIENT_ID`
- Email: `RESEND_API_KEY`, `RESEND_FROM_ADDRESS`
- Media/video: `MEDIA_STORAGE_PATH`, `POST_QUARANTINE_PATH`, `POST_WORK_PATH`, `POST_PUBLIC_STORAGE_PATH`, `POST_SIGNING_SECRET`
- Payments: `PAYSTACK_SECRET_KEY`, `PAYPAL_CLIENT_ID`, `PAYPAL_CLIENT_SECRET`, `PAYPAL_WEBHOOK_ID`
- Verification: `KIMI_API_KEY`, `LIVENESS_CHALLENGE_SECRET`

Generate independent random values for the JWT and signing/liveness secrets. Provider credentials must come from the corresponding Google, Resend, Paystack, PayPal, and Kimi accounts. `PORT` is provided by Render. Firebase push credentials and TURN configuration are optional; leaving them unset disables those integrations.

## Persistent media storage

The backend writes media and video work files to local paths. Render services have ephemeral filesystems unless a persistent disk is attached; uploads can be lost on redeploy/restart without durable storage. Before enabling user uploads, attach a persistent disk at `/var/telefam` and point the `MEDIA_*_PATH` and `POST_*_PATH` settings to subdirectories, or implement an external object-storage backend. The free web-service plan is not suitable for production, and the free Postgres plan expires after 30 days.
