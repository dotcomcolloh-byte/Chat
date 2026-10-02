# Telefam production fixes

UI layout and styling were intentionally left unchanged.

## Fixed
- Registered, complete Telefam users are added to Contacts/Discover as a fallback pool, while preserving contact/mutual/nearby/new-user ranking signals.
- Posts remain `DRAFT` while media processing finishes; finalization now atomically applies metadata and changes the post to `PUBLISHED`.
- Feed queries therefore only expose fully published posts.
- Large-video client compression now uploads the compressed file's actual byte length, preserving resumable upload correctness.
- Android outbox retries now distinguish transient transport/5xx/408/425/429 failures from permanent 4xx failures.
- Permanent outbox failures are removed instead of retrying forever.
- WorkManager uses `APPEND_OR_REPLACE` so actions queued while a worker is finishing cannot be stranded by `KEEP`.
- System back navigation is routed through the existing app screen state, including Chats, Contacts, Feeds, Feed Search, Profiles, post creation, and chat sub-screens.
- Following your own feed post no longer creates an invalid optimistic follow state.

## Verification
The source was statically checked for modified declarations, platform `expect/actual` coverage, balanced braces/parentheses, route/data-flow consistency, and the presence of the new retry/publication/navigation paths.

The supplied project does not contain a Gradle wrapper and the environment has no `gradle` executable, so a full Android/KMP compile could not be executed in this environment.

## Profile review fixes (round 2)

### Critical
- **Likes stat wired to Friends list**: the public profile's third stat is now **Friends** and displays `friendsCount` (already computed server-side); it opens `ConnectionListKind.FRIENDS`. The `Likes` label is no longer misused.
- **Share Profile connected**: the existing `ShareProfileScreen` (QR, copy link, share) is now reachable from the 3-dot menu on both owner and public profiles, and from the red Send brand mark in the top bar (previously decorative). Navigation: `Screen.ShareProfile(userId)` in `MainActivity`.
- **riskLevel removed from the public profile DTO** (backend `ConnectService.ProfileDetailsDto` and shared `ConnectModels.kt`). Risk scoring stays server-side for rate limiting only.
- **Saved/Locked privacy verified**: `FeedService.userTabPosts` returns an empty page for non-owners on LOCKED/SAVED; `userPosts` excludes PRIVATE posts for non-owners; the single-post manifest endpoint returns 403 for PRIVATE posts unless owner. No changes needed.

### Major
- **Website field added end-to-end**: `Users.website` column, `ProfileDetailsDto.website`, `AccountDetailsDto.website`, `EditProfileRequest.website` (empty string clears), `Validators.isValidWebsite` (http/https only), display row on the profile, and an edit field with client-side validation.
- **Location editing**: `EditProfileRequest.locationName` (empty clears), validator, and an Edit Profile field.
- **Edit Profile screen wired up**: `Screen.EditProfile` in `MainActivity` — loads `GET /api/account`, saves via `PUT /api/account/profile`, uploads a new photo through `SettingsApi.uploadProfileImage`, and supports **Remove photo** (`removeAvatar = true` clears `profileImageMediaId` server-side). Edit profile / Settings buttons on the owner profile are now connected.
- **DB-level pagination for connection lists**: followers/following/subscribers now page with SQL `LIMIT`/`OFFSET` over a narrow projection with blocked users excluded in SQL (limit+1 probe for `nextOffset`). Friends remains set-derived (mutual follow OR accepted message request) and is paged after derivation.
- **Follow/list state sync**: `toggleFollowInList` now writes the server's authoritative `FollowStateDto` into every loaded list containing that user, invalidates stale Friends/Followers pages, and adjusts the visible profile header counts (own `followingCount` / their `followerCount`).

### Minor / polish
- **Pull-to-refresh** on the profile (`PullToRefreshBox`) refreshes the profile and the active post tab.
- **Distinct post failure state**: "Couldn't load posts" + Retry when the tab load fails, separate from the empty state.
- **Dead filter affordance removed**: the non-owner "All posts ▾ / tune" row (no backing implementation) was removed.
- **Subscribe flow connected:** the public profile Subscribe button opens the plan picker and provider checkout; payment confirmation is verified server-side.
- The owner 3-dot menu keeps Creator tools / Insights / Archive as coming-soon placeholders, per instruction.

## Verification
Static review only (no Gradle wrapper in the project and no Gradle/Android SDK in this environment): balanced braces/parens, DTO parity between backend and shared wire models, route/handler consistency, and exhaustive `when` coverage for the two new screens.

## Paid creator subscriptions
- Payments now use subscriber-scoped idempotency keys and guarded settlement transitions, so polling/webhooks cannot activate the same charge twice.
- Added encrypted Paystack reusable authorization storage, scheduled renewal attempts, bounded retries and configurable grace periods. The key is optional; renewals stay disabled unless `PAYMENT_TOKEN_ENCRYPTION_KEY` is configured. PayPal remains a one-off checkout.
- Cancellation now stops future billing at the end of the paid period; expiry/grace sweeps remove platform subscriber links only after no paid entitlement remains.
- Added authenticated fan subscription and payment-history endpoints and a **My subscriptions** screen with cancel-at-period-end and recent payment records.
- Added subscriber-only post creation metadata and backend entitlement checks for feeds, manifests, and signed media delivery. Restricted captions/media are omitted for non-subscribers; signed subscriber-media links are short-lived and viewer-bound.
- Archived plans remain available to existing subscriptions but are not offered for new purchases.
- Existing subscriptions snapshot their amount, currency and billing cadence; creator plan edits do not silently alter a subscriber's renewal price.
- Added a creator payments list and confirmed full-refund action. Refund requests are unique per payment, persisted before provider calls, and reconciled through Paystack refund lookup/webhooks or PayPal refund/capture status endpoints. Ambiguous Paystack results are never re-posted; PayPal recovery uses the same request ID with a three-replay cap.
- Payment records become `REFUNDED` only after provider confirmation. Confirmed refunds are removed from earnings; access is revoked only when the refunded payment funded the current period. Partial/conflicting provider refunds become `NEEDS_REVIEW`, not success.
- **Not implemented:** partial refunds, subscriber tier changes/proration, lifecycle notifications, and paid-benefit gating beyond posts. PayPal recurring billing is unavailable in the current one-off order integration. Provider states still unresolved after the safe reconciliation path require manual review; the app does not automatically reissue Paystack refunds.
- `PAYMENT_TOKEN_ENCRYPTION_KEY` must remain stable and decode from Base64 to 32 bytes. If it is absent, malformed, or changed, automatic renewals are disabled rather than storing or charging raw payment credentials.
- Full compilation was not available: this source copy has no Gradle wrapper and the environment has no Gradle executable. The checks in this delivery are static, not build verification.
