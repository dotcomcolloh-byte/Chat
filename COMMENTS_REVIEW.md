# Comments feature — implementation & privacy review

## What ships

**Feeds comments** (bottom sheet from the comment rail button, in Feeds, Feed Search watch overlay, and Profile watch overlay):

- Paginated top-level comments (keyset cursor, `GET /api/feeds/{postId}/comments?cursor=&limit=`), infinite scroll with skeleton rows, and an offline banner + retry state.
- Threaded replies, two levels deep, loaded lazily: "View N replies" expands in place (`GET .../{rootId}/replies`), "View more replies" paginates inside the thread, "Hide replies" collapses.
- Comment likes (optimistic with server reconciliation and rollback), relative timestamps, the red "+" follow badge on avatars, tap avatar/name → profile.
- Read more / Read less on long bodies (>180 chars).
- Autolinking: URLs open in the in-app browser, @mentions resolve via the user directory and open the profile, #hashtags open feed search.
- @mention autocomplete in the composer (debounced directory search while typing a handle).
- Long-press on a comment (or its media) opens the action menu: Copy, Edit (author, text comments, 60-minute window, `edited` marker), Delete (author, or the post owner moderating their own post — soft delete so threads stay intact), Pin/Unpin to top (post owner only, one pin per post, shown in a dedicated slot), Report.
- The 3-dots affordance on every comment opens the structured report form (reason picker + optional details) → `POST /api/comments/{id}/report`.
- Photo comments: native picker → crop → client compression (`rememberImagePickerCropCompress`) → **staged preview in the composer (review before sending)** → upload through the standard `/api/media/upload` pipeline (server-side decode + re-encode) → stored as a MediaAsset. Rendered sticker-size; tap opens the full viewer with a Download button and the same long-press actions.
- Sticker/GIF comments reuse the chats' GIPHY pipeline (same `GiphyApi`, sticker/GIF tabs, debounced search, infinite grid), staged for preview before sending.
- Search within a post's comments (`GET .../comments/search?q=`) with its own pagination.
- **Send Stars** (star icon in the composer): sheet matching the reference — creator card, live balance chip, fixed star tiers priced in the viewer's own currency (server derives currency from the profile's country — the same catalog as the campaign star store). Stars in the sheet and tiers spin (3D rotateY + orbit). Send checks the wallet server-side; **402 → the existing campaign Buy Stars flow** (`Screen.BuyStars`) with no duplicated UI. On success a 3D celebration plays (spinning star + glitter sparkles orbiting the star value; higher tiers add sparkles, a halo ring and faster spin).
- Theme: the whole sheet is Material-theme driven — it follows the app's system light/dark setting.

## Backend

New tables (`com.telefam.comments`, auto-created + RLS-enforced):
- `post_comments` (post, author, parent/root thread keys, kind TEXT|PHOTO|STICKER|GIF, body, mediaId, stickerUrl, mentions JSON, pinnedByOwner, editedAt, deletedAt)
- `comment_likes`, `comment_reports`, `comment_star_gifts` (idempotency-keyed)

Service rules (`CommentService`):
- Every read/write re-derives post visibility in SQL (PRIVATE owner-only, FRIENDS relationship-checked, blocked pairs excluded both directions). Hidden posts 404 — existence is never leaked.
- The post's `commenting` setting gates writes: `OFF` rejects and the client renders "Comments are turned off"; `FRIENDS` limits to friends + owner.
- Photo comments must reference a MediaAsset **owned by the author**; sticker/GIF URLs are whitelist-validated to GIPHY media hosts; bodies are capped at 2000 chars.
- Star gifts are ledger-backed and atomic: conditional debit on the sender's star wallet, paired signed ledger entries for sender/recipient (`GIFT_SEND` / `GIFT_RECEIVE`), recipient wallet credit, a `star_transactions` analytics row (Stars screens) and a `WalletService.recordEarning(STARS)` entry (Wallet screens) — all idempotent by the client's `Idempotency-Key`, so a retried tap can never double-charge. Concurrent sends are serialized by the conditional UPDATE; losers get 402 with the real balance.
- Feed cards now carry the real `commentCount` (non-deleted comments, batched per page).

Privacy: RLS policies for all four tables are in `db/rls_policies.sql` (privileged server reads, actor-scoped writes), following the existing engagement-table pattern.
