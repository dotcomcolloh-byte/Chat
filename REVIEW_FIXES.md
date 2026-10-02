# Telefam — End-to-End Static Review & Production Fixes

Full end-to-end static review of the Telefam app (Kotlin Multiplatform Compose client + Ktor/Postgres backend). Below is everything that was found and fixed, grouped by area. UI was left untouched except where a feature required it (conversation list multi-select, blocked-composer states).

---

## 1. Security — backend authoritative (highest priority)

### Combined risk scoring across auth + actions
- **New `RiskService` (`backend/.../risk/RiskService.kt`)**: one append-only `risk_events` table (`user_id`, `kind`, `weight`, `detail`, `created_at`, indexed `(user_id, created_at)`), 30-day decay window, `recentPoints()` summed 0–100. Server-only RLS policy — never exposed to clients.
- Signals now recorded from every domain:
  - **Auth**: `LOGIN_FAILURE` (+5), `LOGIN_LOCKOUT` (+25) in `LoginAttemptService`; `OTP_COOLDOWN` (+20), `OTP_VERIFY_FAILED` (+4) in `OtpService`; `TOKEN_REUSE_DETECTED` (+60) in `JwtService` (refresh-token family theft); `PASSWORD_RESET` (+10) in `AuthService`.
  - **Wallet**: `PAYOUT_METHOD_CHANGE` (+15), `PAYOUT_FAILED` (+10), `PAYOUT_BURST` (+15 — >3 payout requests in 24h).
- `WalletService.riskScore()` now adds `RiskService.recentPoints(userId) / 2` to its financial signals — an account behaving badly in auth is treated as risky for withdrawals too.

### Withdrawals
- **Large withdrawals always need review**: `LARGE_WITHDRAWAL_REVIEW_MINOR` env (default 50000 minor units). Any payout at/above the threshold forces effective risk level HIGH → `SECURITY_CHECK`, funds stay reserved, user sees a "routine security review" message, admin approves via the existing review queue. Backend is the sole authority — the client cannot influence the level.
- **Payout rate limiting**: new `wallet` rate-limit bucket (6 req/min/IP) on `POST /api/wallet/payouts`.
- **Payout reference lookup fix**: `payoutIdForReference` previously scanned all payout rows and compared in memory — would degrade badly as users grow. Now an indexed lookup on `provider_ref` / new `public_ref` column / id, with a one-time backfill for legacy rows; `idx_payout_public_ref` index created at boot.

### RLS / database
- **Fixed broken `verification_payments_owner_only` policy** in `rls_policies.sql` — it was a `CREATE POLICY` with no `USING`/`WITH CHECK` (syntax error that would abort the boot-time RLS pass). Now a proper owner-only policy.
- Added owner-only RLS for `device_tokens` and server-only for `risk_events`.

### Concurrency
- Removed `runBlocking` calls **inside** Exposed transactions in `SocialService` (`listPendingRequests`, `listBlocked`) — a deadlock/thread-starvation risk under load; both are now two-phase (query, then resolve outside the tx).

---

## 2. Push notifications (Firebase configs installed)

- `google-services.json` → `androidApp/`, `GoogleService-Info.plist` → `iosApp/` (project `studio-8173715166-ba023`, package `com.telefam.app` on both).
- **Content-free server pushes**: `E2EEService.sendEnvelope` (block-drop + non-control categories only) → `CallPushNotifier.notifyNewMessage` → FCM data-only high-priority wake (Android) / APNs alert (iOS). Dead device tokens are pruned on 404/410. No plaintext ever leaves the server.
- **Android chat notifications** (`ChatNotificationHelper`): channel `telefam_chat_messages`, per-peer notification id, actions:
  - **Reply** — inline `RemoteInput`; `ChatActionReceiver` → `ChatActionBus` → `ChatService.send` (E2EE encrypted, optimistic UI, retry queue).
  - **Mark as read** — marks conversation read and sends read receipts.
  - Tap — deep-links into the conversation (`com.telefam.app.chat.OPEN`).
- `TelefamFirebaseMessagingService` handles `kind=chat_message`: requires an active session, cold-starts the E2EE stack via shared `HttpClientHolder`, pulls and decrypts the pending envelopes (`ChatPushBridge`), and posts a notification with the real decrypted preview (📷 Photo etc. for media). `kind=incoming_call` (Accept/Decline full-screen intent) unchanged.

## 3. Blocking — end to end

- **Server-authoritative drop**: `E2EEService.sendEnvelope` silently drops envelopes when the recipient has blocked the sender (random UUID returned so the sender can't distinguish block from offline). RLS-scoped insert; push callback runs outside the transaction.
- **New `GET /api/social/blocked-status/{peerId}`** returning `{ iBlocked, blockedMe }` (two-phase, no runBlocking).
- **Chat UI** (`ChatScreen`): composer is replaced by a `BlockedComposer` bar —
  - I blocked them → "You blocked this person" + **Unblock** button (wired through the offline outbox: `DELETE /api/social/blocked/{id}`, queued when offline).
  - They blocked me → "You can't contact this person", composer disabled.
- Blocked users continue to appear in the block list (existing privacy settings screen).

## 4. Conversation list (HomeScreen rewrite)

- **Long-press multi-select** with a contextual red top bar: Close / count / mark-read / archive / delete.
- **Archive** → outbox `POST /api/social/archived/{id}` (offline-queued, `OutboxSyncWorker` retries). **Delete** → confirmation dialog, wipes local conversation (messages, draft, settings, peer cache). **Mark as read** in bulk.
- **Unread badges** (red circle, "99+" cap) from a new `unreadCount` subquery in `selectConversations` — no extra round-trips.
- **Timestamps**: HH:mm today / weekday this week / dd/MM/yyyy older.
- **Live search**: search icon toggles an inline field, filters as you type, clear-X, no-match empty state.
- 52dp avatars, selection highlight, standard 16dp corner language — consistent with the design system.

## 5. UI / buttons audit

- Audited every `Button`/touch target in `shared/ui`: the design system (`TelefamPrimaryButton` 52dp, 16dp radius; `TelefamOutlinedButton` 52dp, 12dp radius) is applied consistently; all interactive targets ≥ 44dp; profile action buttons 48dp min. No oversized or undersized violations found, so no cosmetic changes were made (per "don't modify UI unless crucial").

## 6. Profiles, posts, feeds, caching, realtime — review findings

- Feeds/posts/profile screens: error and offline states already flow through the shared `Result`/snackbar pattern and the offline outbox covers privacy/social mutations; no blockers found.
- Realtime: presence, typing, and mailbox hints over WebSocket with the 4s polling fallback verified intact; conversation list updates come from local SQLDelight + polling, so no manual refresh is needed.
- Caching: SQLDelight cache verified (peers, messages, drafts, settings); added a shared lazily-created `HttpClientHolder` so the push path and UI share one configured client instead of building ad-hoc ones.

## 7. Production scaling concerns addressed

- Payout-reference full-table scan → indexed lookup (§1).
- `runBlocking`-in-transaction deadlocks removed (§1).
- Push fan-out prunes dead device tokens instead of accumulating failures.
- Risk queries use the composite `(user_id, created_at)` index over a bounded 30-day window.

---

### Ops notes
- New env var: `LARGE_WITHDRAWAL_REVIEW_MINOR` (default 50000 = 500.00 in 2-decimal currencies).
- FCM service account must be in `FCM_SERVICE_ACCOUNT_JSON` for chat wake + call pushes (already required for calls).
- Boot creates `risk_events` table + indexes automatically (`createMissingTablesAndColumns`); RLS policies apply from `rls_policies.sql`.
