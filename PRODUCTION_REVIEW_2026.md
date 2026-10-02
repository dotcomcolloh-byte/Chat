# Telefam — Production Review & Fixes (2026-10)

Full static review of the whole app (347 Kotlin files: shared Compose UI, Android shell, Ktor backend). All fixes applied in place; no UI layout, navigation, or behavior changed except where listed.

## 1. Brand logo (Telegram-style, red)
- New vector brand mark `shared/.../ui/components/TelefamLogo.kt`: a paper-plane drawn as Canvas paths (crisp at any size, no image assets), rendered in brand red `#D32323` (or white-on-red / red-on-white variants via parameters).
- Wired into: **Home top bar** (white circle + red plane next to the wordmark), **SignUpScreen** and **LoginScreen** headers (replaces the generic `Icons.Filled.Send` placeholder).
- Android launcher icon: `ic_launcher_foreground.xml` was a white "T" — replaced with the white paper-plane glyph; adaptive-icon background corrected to the exact brand red `#D32323` (was `#D32F2F`).

## 2. Text/emoji glyphs used as UI controls → Material icons
| Glyph | Replacement | Files |
|---|---|---|
| `←` back text | `Icons.AutoMirrored.Filled.ArrowBack` in the 44dp IconButton | WalletScreen, WalletSubScreens (shared WalletTopBar) |
| `›` / `‹` chevrons | `Icons.AutoMirrored.Filled.KeyboardArrowRight/Left` | FeedScreen, CreatorMenuScreen (3), WalletScreen (5), CreateCampaignScreen ("See All / Show Less"), SeriesVideosScreen ("Choose Videos") |
| `✓` checkmarks | `Icons.Filled.Check` (tinted, sized) | CreateCampaignScreen (6: stepper, video cards, goal cards, country rows, budget card), SeriesVideosScreen, EditProfileScreen |
| `✕` remove/error | `Icons.Filled.Close` | CreateCampaignScreen country chips, VerificationFlowScreens rejection header |
| `→` in CTA | `Icons.AutoMirrored.Filled.ArrowForward` after label | GetVerifiedScreen ("Get Verified Now") |
| `★` | `Icons.Filled.Star` | FeedInfoOverlay |
| `↑`/`↓` trend glyphs | `Icons.AutoMirrored.Filled.TrendingUp/TrendingDown` + number | CreatorCommon stat card, WalletScreen transactions, CreatorSubscriptionsScreen |
| `📷 🎤 🎬 📊 👤 📍 📄` in chat previews | Real Material icons (`Image`, `Mic`, `PlayArrow`, `Poll`, `Person`, `LocationOn`, `Description`) rendered before the preview text | HomeScreen (`previewIconFor`) |
| Same set in push-notification bodies | Plain localized text (notifications can't render inline icons) | ChatPushBridge |
| Country flag emoji | ISO-2 code text (flags render inconsistently across Android OEMs) | CountryPickerDropdown, CreateCampaignScreen |

`EmojiPicker.kt` untouched — that is user content (reactions/stickers), not UI chrome.

## 3. Offline error handling — no top-bar banners
- Removed the `OfflineNotice()` strip pinned under top bars on 13+ screens (Dashboard, Analytics, Stars, Monetization, Campaigns, Subscriptions, etc.) and deleted the composable.
- Offline behavior is now: cached data renders silently; actions that need the network are **disabled while offline** (`enabled = !state.offline` on Buy/Create/Continue); failures without cache still show the existing ErrorCard with Retry.
- FeedScreen's pinned "No internet" bar replaced with a one-shot transient Snackbar (fires on connectivity drop).

## 4. Buttons / dead controls
- Swept for `onClick = {}` / `clickable { }` no-ops: only intentional tap-blockers (media viewers' long-press menus, loading overlay) remain.
- HelpSupportScreen: dead `AssistChip(onClick = {})` status chip converted to a non-interactive status pill (it never had an action — it now correctly doesn't look tappable).
- SeriesVideosScreen: "Manage Series" was non-clickable text styled like a link — restyled as a plain section caption so nothing looks broken.

## 5. Verification
- Brace/paren balance re-checked on every edited file.
- All replacement icons come from `compose.materialIconsExtended` (already a dependency).
- Remaining `→` occurrences are prose only (FAQ help text, KDoc comments, backend comments) — not UI controls.

## Notes
- `Country.flagEmoji` field kept on the data model (harmless, computed) but no longer rendered.
- Could not run a Gradle build in the review environment (no Android SDK); all edits are syntactically verified and use only already-imported or newly imported Material icon APIs.
