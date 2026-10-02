# Telefam — Campaigns / Subscriptions / Wallet Review & Campaign System Upgrade

## What changed in this build

### 1. Audience targeting (gap #1) — implemented
- `CreateCampaignRequest` now carries an `audience` payload: `mode` (AUTO | CUSTOM),
  `countries` (ISO-2 list, full country picker with search in the app), `ageMin`/`ageMax`,
  `gender` (MALE | FEMALE | all), and `interests` (16-tag catalog, max 8).
- New columns on `campaigns`: `audience_mode`, `target_countries`, `age_min`, `age_max`,
  `target_gender`, `target_interests`, `paused_at` (auto-created by `createMissingTablesAndColumns`).
- **Delivery-side enforcement** in `CampaignService.activeSponsoredForViewer()`: the viewer's
  country (dial-code → ISO), gender and age (from `date_of_birth`) are matched against the
  campaign's filters; interest targeting is matched against the boosted post's hashtags.
  Viewers with unset attributes are never silently excluded. Server-side only — the client
  never decides eligibility.
- The creation flow is now: **Goal → Content → Audience → Budget → Review → Pay**, with a
  step indicator and per-step validation.

### 2. Review / confirmation screen (gap #2) — implemented
New final "Review" step before any stars are charged, showing: goal, creative thumbnail,
audience summary, **estimated unique reach**, duration, daily cost, total, current balance,
and **balance after campaign** (red when insufficient). The charge only happens after
"Start Campaign".

### 3. Campaign management dashboard (gap #3) — implemented
New `CampaignDashboardScreen` ("My Campaigns", added to the Creator Menu):
- Tabs: **Active** (running + paused) and **History** (exhausted / expired / canceled).
- Per campaign: status chip, unique-reach progress bar vs the tier cap, impressions,
  clicks, CTR, stars spent (net of refunds), remaining days / end date, audience summary.
- Actions: **Pause** (freezes the delivery clock), **Resume** (shifts `endsAt` by the
  paused duration), **Cancel** — with confirmation; the server refunds unused **full days**
  pro-rata via a ledger-backed, idempotent `CAMPAIGN_REFUND` entry.

### 4. Advertiser analytics (gap #4) — implemented
- `SponsoredEvents.kind` widened: `IMPRESSION | CLICK | PROFILE_VISIT | FOLLOW | DESTINATION_CLICK`
  (all deduped per campaign+viewer+kind).
- New endpoint `GET /api/campaigns/{id}/analytics` returns the deduped funnel + CTR +
  unique viewers + stars spent. The dashboard's Analytics dialog renders the goal-appropriate
  funnel (Get Sales → destination actions; Followers/Profile Boost → profile visits → follows).
- Client now records `DESTINATION_CLICK` when a Get Sales link opens and
  `PROFILE_VISIT` + `FOLLOW` when the Follow CTA is tapped.

### 5. "See All" wired (gap #5) — implemented
"See All ›" now expands the video picker into a full 3-column grid of all loaded videos
(toggles back to "Show Less ‹").

### 6. Profile Boost naming (gap #6) — clarified in-product
Kept the video-as-creative model; the goal step now explains: the video is the promotional
creative, the profile is the destination.

### 7. Reach terminology (the "important issue") — fixed
UI now says **"Estimated unique reach"** everywhere (tier picker, review screen, dashboard).
`maxReach` is documented as a unique-viewer cap (impressions are deduped per viewer), and the
budget step carries an explicit "estimate, not a guarantee" note.

### 8. Ad injection after 4 videos — implemented
`FeedService.withSponsored()` now inserts the sponsored item at index 4 (after the first 4
organic videos) instead of index 2.

### New/updated endpoints
| Method | Path | Purpose |
|---|---|---|
| GET | `/api/campaigns/{id}/analytics` | Deduped funnel analytics |
| POST | `/api/campaigns/{id}/pause` | Pause (freezes clock) |
| POST | `/api/campaigns/{id}/resume` | Resume (extends endsAt) |
| POST | `/api/campaigns/{id}/cancel` | Cancel + pro-rata refund |

All management endpoints are owner-scoped, return 404 for foreign campaigns and 409 for
invalid state transitions.

---

## Review notes (pre-existing code)

### Campaigns — what was already solid
- Atomic wallet debit (conditional `UPDATE balance >= cost`), unique idempotency keys,
  provider-verified star purchases, webhook signature + replay guards.

### Wallet (`WalletService`)
Strong design: ledger-derived balances, reserve/release payout state machine, settlement
windows, risk checks, OTP-gated payout methods. Recommendations:
- Surface `withdrawBlockedReason` prominently in the wallet UI (it's computed but easy to miss).
- Add an in-app ledger export (CSV) for creators' accounting.

### Subscriptions (`SubscriptionService` / tables)
Strong: minor-unit amounts server-computed, provider-verified PAID, idempotent initiation,
durable refund rows with reconciliation states, dunning fields (`retryCount`, `nextRetryAt`,
`graceUntil`). Recommendations:
- PayPal checkout has **no renewal token** — PayPal subscribers can't auto-renew. Surface
  "renews manually" in the UI for PayPal-purchased plans.
- No proration on plan switch (price locked per subscriber — acceptable, but document it).
- `cancelAtPeriodEnd` exists; make sure the subscriber UI shows the exact access-until date.

## Still open (next iteration)
1. **Per-goal delivery optimization** (gap #7): goals currently change pricing/CTA but not
   ranking. Next step: score eligible campaigns per viewer by predicted action (e.g. follow
   propensity for FOLLOWERS) once event volume justifies it.
2. **Lookalike/custom-audience uploads** — not yet supported (needs a follower-graph pipeline).
3. **Scheduled campaigns** — campaigns start immediately; add `startsAt` scheduling + a
   "Scheduled" dashboard tab if needed.
4. **Frequency capping** — a viewer can be re-shown different campaigns back-to-back across
   pages; consider a per-viewer hourly sponsored cap.
5. Interest targeting matches post hashtags only — consider a post `category` field for
   more reliable matching.
