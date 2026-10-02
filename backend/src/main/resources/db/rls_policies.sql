-- Row-Level Security: the database itself refuses to return or modify rows that
-- don't belong to the connected session's user, even if application code has a bug.
-- Enforced via a per-transaction session variable (app.current_user_id) set by
-- RlsContext.kt on every authenticated request — never trust the app layer alone.

ALTER TABLE privacy_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE privacy_settings FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS privacy_settings_owner_only ON privacy_settings;
CREATE POLICY privacy_settings_owner_only ON privacy_settings
    USING (user_id = current_setting('app.current_user_id', true)::uuid)
    WITH CHECK (user_id = current_setting('app.current_user_id', true)::uuid);

ALTER TABLE message_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE message_requests FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS message_requests_participant_only ON message_requests;
DROP POLICY IF EXISTS message_requests_participant_select ON message_requests;
DROP POLICY IF EXISTS message_requests_participant_write ON message_requests;
-- SELECT: privileged server connections (no session user) read across users for
-- aggregate/friend-graph computation; app sessions only ever see their own rows.
CREATE POLICY message_requests_participant_select ON message_requests
    FOR SELECT USING (
        current_setting('app.current_user_id', true) IS NULL
        OR sender_id = current_setting('app.current_user_id', true)::uuid
        OR receiver_id = current_setting('app.current_user_id', true)::uuid
    );
-- Writes stay strictly participant-scoped even for privileged connections.
CREATE POLICY message_requests_participant_write ON message_requests
    FOR ALL USING (
        sender_id = current_setting('app.current_user_id', true)::uuid
        OR receiver_id = current_setting('app.current_user_id', true)::uuid
    )
    WITH CHECK (
        sender_id = current_setting('app.current_user_id', true)::uuid
        OR receiver_id = current_setting('app.current_user_id', true)::uuid
    );

ALTER TABLE blocked_users ENABLE ROW LEVEL SECURITY;
ALTER TABLE blocked_users FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS blocked_users_owner_only ON blocked_users;
CREATE POLICY blocked_users_owner_only ON blocked_users
    USING (blocker_id = current_setting('app.current_user_id', true)::uuid)
    WITH CHECK (blocker_id = current_setting('app.current_user_id', true)::uuid);

-- A blocked user's session may see rows where THEY are the blocked party, solely so directory search can hide
-- people who blocked them. Nothing in the API exposes these rows to the blocked user.
DROP POLICY IF EXISTS blocked_users_blocked_party_can_see ON blocked_users;
CREATE POLICY blocked_users_blocked_party_can_see ON blocked_users
    FOR SELECT USING (blocked_id = current_setting('app.current_user_id', true)::uuid);

ALTER TABLE archived_chats ENABLE ROW LEVEL SECURITY;
ALTER TABLE archived_chats FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS archived_chats_owner_only ON archived_chats;
CREATE POLICY archived_chats_owner_only ON archived_chats
    USING (user_id = current_setting('app.current_user_id', true)::uuid)
    WITH CHECK (user_id = current_setting('app.current_user_id', true)::uuid);

-- E2EE key-distribution and envelope-relay tables: server relays only public keys,
-- prekeys, and opaque ciphertext, so RLS mostly protects the sensitive "one-time
-- prekey supply" and "who can read my mailbox" properties rather than confidentiality
-- of the payloads themselves (payloads are already encrypted end-to-end).

ALTER TABLE device_identities ENABLE ROW LEVEL SECURITY;
ALTER TABLE device_identities FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS device_identities_read_any_write_own ON device_identities;
-- Identity PUBLIC keys must be readable by anyone (that's the whole point - a peer
-- needs your public key to start a session), but only you can register/update your own device.
CREATE POLICY device_identities_read_any_write_own ON device_identities
    FOR SELECT USING (true);
DROP POLICY IF EXISTS device_identities_write_own ON device_identities;
CREATE POLICY device_identities_write_own ON device_identities
    FOR ALL USING (user_id = current_setting('app.current_user_id', true)::uuid)
    WITH CHECK (user_id = current_setting('app.current_user_id', true)::uuid);

ALTER TABLE signed_prekeys ENABLE ROW LEVEL SECURITY;
ALTER TABLE signed_prekeys FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS signed_prekeys_read_any ON signed_prekeys;
CREATE POLICY signed_prekeys_read_any ON signed_prekeys FOR SELECT USING (true);
DROP POLICY IF EXISTS signed_prekeys_write_own ON signed_prekeys;
CREATE POLICY signed_prekeys_write_own ON signed_prekeys
    FOR INSERT WITH CHECK (user_id = current_setting('app.current_user_id', true)::uuid);
DROP POLICY IF EXISTS signed_prekeys_delete_own ON signed_prekeys;
CREATE POLICY signed_prekeys_delete_own ON signed_prekeys
    FOR DELETE USING (user_id = current_setting('app.current_user_id', true)::uuid);

ALTER TABLE one_time_prekeys ENABLE ROW LEVEL SECURITY;
ALTER TABLE one_time_prekeys FORCE ROW LEVEL SECURITY;
-- Any authenticated request may SELECT+DELETE (claim/consume) a prekey - that "claim by
-- consuming" is the actual security property (single use), not row ownership on read.
DROP POLICY IF EXISTS one_time_prekeys_claim_any ON one_time_prekeys;
CREATE POLICY one_time_prekeys_claim_any ON one_time_prekeys FOR SELECT USING (true);
DROP POLICY IF EXISTS one_time_prekeys_delete_any ON one_time_prekeys;
CREATE POLICY one_time_prekeys_delete_any ON one_time_prekeys FOR DELETE USING (true);
DROP POLICY IF EXISTS one_time_prekeys_write_own ON one_time_prekeys;
CREATE POLICY one_time_prekeys_write_own ON one_time_prekeys
    FOR INSERT WITH CHECK (user_id = current_setting('app.current_user_id', true)::uuid);

ALTER TABLE message_envelopes ENABLE ROW LEVEL SECURITY;
ALTER TABLE message_envelopes FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS message_envelopes_recipient_reads ON message_envelopes;
CREATE POLICY message_envelopes_recipient_reads ON message_envelopes
    FOR SELECT USING (recipient_id = current_setting('app.current_user_id', true)::uuid);
DROP POLICY IF EXISTS message_envelopes_recipient_deletes ON message_envelopes;
CREATE POLICY message_envelopes_recipient_deletes ON message_envelopes
    FOR DELETE USING (recipient_id = current_setting('app.current_user_id', true)::uuid);
DROP POLICY IF EXISTS message_envelopes_sender_inserts_as_self ON message_envelopes;
CREATE POLICY message_envelopes_sender_inserts_as_self ON message_envelopes
    FOR INSERT WITH CHECK (sender_id = current_setting('app.current_user_id', true)::uuid);

ALTER TABLE chat_preferences ENABLE ROW LEVEL SECURITY;
ALTER TABLE chat_preferences FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS chat_preferences_owner_only ON chat_preferences;
CREATE POLICY chat_preferences_owner_only ON chat_preferences
    USING (user_id = current_setting('app.current_user_id', true)::uuid)
    WITH CHECK (user_id = current_setting('app.current_user_id', true)::uuid);

ALTER TABLE reports ENABLE ROW LEVEL SECURITY;
ALTER TABLE reports FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS reports_reporter_only ON reports;
CREATE POLICY reports_reporter_only ON reports
    FOR SELECT USING (reporter_id = current_setting('app.current_user_id', true)::uuid);
DROP POLICY IF EXISTS reports_insert_as_self ON reports;
CREATE POLICY reports_insert_as_self ON reports
    FOR INSERT WITH CHECK (reporter_id = current_setting('app.current_user_id', true)::uuid);


-- The app's runtime DB role must NOT be a superuser/table owner (RLS is bypassed for
-- those). Create and use a restricted role for the running application:
--   CREATE ROLE telefam_app LOGIN PASSWORD '...';
--   GRANT SELECT, INSERT, UPDATE, DELETE ON privacy_settings, message_requests, blocked_users,
--     archived_chats, device_identities, signed_prekeys, one_time_prekeys, message_envelopes
--     TO telefam_app;
-- Set DATABASE_USER in .env to telefam_app, never to the table-owning/admin role.

-- Feed engagement tables: every row belongs to exactly one acting user
-- (liker / saver / viewer / reporter / follower). Nobody may read another
-- user's engagement rows; aggregate counts are computed server-side from a
-- privileged connection, so clients never need cross-user row access.
ALTER TABLE post_likes ENABLE ROW LEVEL SECURITY;
ALTER TABLE post_likes FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS post_likes_owner_only ON post_likes;
CREATE POLICY post_likes_owner_only ON post_likes
    USING (current_setting('app.current_user_id', true) IS NULL OR user_id = current_setting('app.current_user_id', true)::uuid)
    WITH CHECK (user_id = current_setting('app.current_user_id', true)::uuid);

ALTER TABLE post_saves ENABLE ROW LEVEL SECURITY;
ALTER TABLE post_saves FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS post_saves_owner_only ON post_saves;
CREATE POLICY post_saves_owner_only ON post_saves
    USING (current_setting('app.current_user_id', true) IS NULL OR user_id = current_setting('app.current_user_id', true)::uuid)
    WITH CHECK (user_id = current_setting('app.current_user_id', true)::uuid);

ALTER TABLE post_views ENABLE ROW LEVEL SECURITY;
ALTER TABLE post_views FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS post_views_owner_only ON post_views;
CREATE POLICY post_views_owner_only ON post_views
    USING (current_setting('app.current_user_id', true) IS NULL OR viewer_id = current_setting('app.current_user_id', true)::uuid)
    WITH CHECK (viewer_id = current_setting('app.current_user_id', true)::uuid);

ALTER TABLE post_reshares ENABLE ROW LEVEL SECURITY;
ALTER TABLE post_reshares FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS post_reshares_owner_only ON post_reshares;
CREATE POLICY post_reshares_owner_only ON post_reshares
    USING (current_setting('app.current_user_id', true) IS NULL OR user_id = current_setting('app.current_user_id', true)::uuid)
    WITH CHECK (user_id = current_setting('app.current_user_id', true)::uuid);

ALTER TABLE not_interested ENABLE ROW LEVEL SECURITY;
ALTER TABLE not_interested FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS not_interested_owner_only ON not_interested;
CREATE POLICY not_interested_owner_only ON not_interested
    USING (current_setting('app.current_user_id', true) IS NULL OR user_id = current_setting('app.current_user_id', true)::uuid)
    WITH CHECK (user_id = current_setting('app.current_user_id', true)::uuid);

ALTER TABLE post_reports ENABLE ROW LEVEL SECURITY;
ALTER TABLE post_reports FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS post_reports_reporter_only ON post_reports;
CREATE POLICY post_reports_reporter_only ON post_reports
    USING (current_setting('app.current_user_id', true) IS NULL OR reporter_id = current_setting('app.current_user_id', true)::uuid)
    WITH CHECK (reporter_id = current_setting('app.current_user_id', true)::uuid);

-- Follow graph: both parties may see the relationship (needed to render
-- follower lists and "Following" feeds); only the follower may create/delete it.
ALTER TABLE follows ENABLE ROW LEVEL SECURITY;
ALTER TABLE follows FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS follows_parties_can_see ON follows;
CREATE POLICY follows_parties_can_see ON follows
    FOR SELECT USING (
        current_setting('app.current_user_id', true) IS NULL
        OR follower_id = current_setting('app.current_user_id', true)::uuid
        OR followee_id = current_setting('app.current_user_id', true)::uuid
    );
DROP POLICY IF EXISTS follows_follower_writes ON follows;
CREATE POLICY follows_follower_writes ON follows
    FOR INSERT WITH CHECK (follower_id = current_setting('app.current_user_id', true)::uuid);
DROP POLICY IF EXISTS follows_follower_deletes ON follows;
CREATE POLICY follows_follower_deletes ON follows
    FOR DELETE USING (follower_id = current_setting('app.current_user_id', true)::uuid);

-- Contact discovery hashes: strictly owner-only. Hashes of a user's address book
-- are sensitive (they reveal social connections), so even privileged aggregate
-- reads go through the application, never direct user sessions.
ALTER TABLE contact_hashes ENABLE ROW LEVEL SECURITY;
ALTER TABLE contact_hashes FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS contact_hashes_owner_only ON contact_hashes;
CREATE POLICY contact_hashes_owner_only ON contact_hashes
    USING (current_setting('app.current_user_id', true) IS NULL OR user_id = current_setting('app.current_user_id', true)::uuid)
    WITH CHECK (user_id = current_setting('app.current_user_id', true)::uuid);

-- Follow rate-limit counters: the server manages these; a session may only touch its own row.
ALTER TABLE follow_rate_limits ENABLE ROW LEVEL SECURITY;
ALTER TABLE follow_rate_limits FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS follow_rate_limits_owner_only ON follow_rate_limits;
CREATE POLICY follow_rate_limits_owner_only ON follow_rate_limits
    USING (current_setting('app.current_user_id', true) IS NULL OR user_id = current_setting('app.current_user_id', true)::uuid)
    WITH CHECK (user_id = current_setting('app.current_user_id', true)::uuid);

ALTER TABLE subscriptions ENABLE ROW LEVEL SECURITY;
ALTER TABLE subscriptions FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS subscriptions_select ON subscriptions;
CREATE POLICY subscriptions_select ON subscriptions
    FOR SELECT USING (current_setting('app.current_user_id', true) IS NULL
        OR subscriber_id = current_setting('app.current_user_id', true)::uuid
        OR creator_id = current_setting('app.current_user_id', true)::uuid);
DROP POLICY IF EXISTS subscriptions_write ON subscriptions;
CREATE POLICY subscriptions_write ON subscriptions
    FOR ALL USING (subscriber_id = current_setting('app.current_user_id', true)::uuid)
    WITH CHECK (subscriber_id = current_setting('app.current_user_id', true)::uuid);

ALTER TABLE app_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE app_settings FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS app_settings_owner_only ON app_settings;
CREATE POLICY app_settings_owner_only ON app_settings
    USING (user_id = current_setting('app.current_user_id', true)::uuid)
    WITH CHECK (user_id = current_setting('app.current_user_id', true)::uuid);

ALTER TABLE problem_reports ENABLE ROW LEVEL SECURITY;
ALTER TABLE problem_reports FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS problem_reports_owner_only ON problem_reports;
CREATE POLICY problem_reports_owner_only ON problem_reports
    USING (user_id = current_setting('app.current_user_id', true)::uuid)
    WITH CHECK (user_id = current_setting('app.current_user_id', true)::uuid);

ALTER TABLE pending_email_changes ENABLE ROW LEVEL SECURITY;
ALTER TABLE pending_email_changes FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS pending_email_changes_owner_only ON pending_email_changes;
CREATE POLICY pending_email_changes_owner_only ON pending_email_changes
    USING (user_id = current_setting('app.current_user_id', true)::uuid)
    WITH CHECK (user_id = current_setting('app.current_user_id', true)::uuid);

-- ---- Verification & payments (owner-only rows; admin reads use a bypass role) ----
ALTER TABLE verification_payments ENABLE ROW LEVEL SECURITY;
ALTER TABLE verification_payments FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS verification_payments_owner_only ON verification_payments;
CREATE POLICY verification_payments_owner_only ON verification_payments
    USING (current_setting('app.current_user_id', true) IS NULL
        OR user_id = current_setting('app.current_user_id', true)::uuid)
    WITH CHECK (current_setting('app.current_user_id', true) IS NULL
        OR user_id = current_setting('app.current_user_id', true)::uuid);

-- Push device tokens: strictly owner-visible delivery addresses.
ALTER TABLE device_tokens ENABLE ROW LEVEL SECURITY;
ALTER TABLE device_tokens FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS device_tokens_owner_only ON device_tokens;
CREATE POLICY device_tokens_owner_only ON device_tokens
    USING (current_setting('app.current_user_id', true) IS NULL
        OR user_id = current_setting('app.current_user_id', true)::uuid)
    WITH CHECK (current_setting('app.current_user_id', true) IS NULL
        OR user_id = current_setting('app.current_user_id', true)::uuid);

-- Risk events: internal-only. No client session may read or write them at all;
-- only privileged server connections (no session user set) touch this table.
ALTER TABLE risk_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE risk_events FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS risk_events_server_only ON risk_events;
CREATE POLICY risk_events_server_only ON risk_events
    USING (current_setting('app.current_user_id', true) IS NULL)
    WITH CHECK (current_setting('app.current_user_id', true) IS NULL);


ALTER TABLE verification_applications ENABLE ROW LEVEL SECURITY;
ALTER TABLE verification_applications FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS verification_applications_owner_only ON verification_applications;
CREATE POLICY verification_applications_owner_only ON verification_applications
    USING (current_setting('app.current_user_id', true) IS NULL
        OR user_id = current_setting('app.current_user_id', true)::uuid)
    WITH CHECK (current_setting('app.current_user_id', true) IS NULL
        OR user_id = current_setting('app.current_user_id', true)::uuid);

ALTER TABLE verification_badges ENABLE ROW LEVEL SECURITY;
ALTER TABLE verification_badges FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS verification_badges_owner_only ON verification_badges;
CREATE POLICY verification_badges_owner_only ON verification_badges
    USING (current_setting('app.current_user_id', true) IS NULL
        OR user_id = current_setting('app.current_user_id', true)::uuid)
    WITH CHECK (current_setting('app.current_user_id', true) IS NULL
        OR user_id = current_setting('app.current_user_id', true)::uuid);

-- Audit rows are insert-only for the app role; no UPDATE/DELETE policy is created on purpose.
ALTER TABLE verification_audit ENABLE ROW LEVEL SECURITY;
ALTER TABLE verification_audit FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS verification_audit_insert ON verification_audit;
CREATE POLICY verification_audit_insert ON verification_audit
    FOR INSERT WITH CHECK (true);

-- Creator program: monetization applications are strictly owner-visible.
ALTER TABLE monetization_applications ENABLE ROW LEVEL SECURITY;
ALTER TABLE monetization_applications FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS monetization_applications_owner_only ON monetization_applications;
CREATE POLICY monetization_applications_owner_only ON monetization_applications
    USING (current_setting('app.current_user_id', true) IS NULL
        OR user_id = current_setting('app.current_user_id', true)::uuid)
    WITH CHECK (current_setting('app.current_user_id', true) IS NULL
        OR user_id = current_setting('app.current_user_id', true)::uuid);

-- Stars: a session may see gifts it sent or received; writes are checked the same way.
ALTER TABLE star_transactions ENABLE ROW LEVEL SECURITY;
ALTER TABLE star_transactions FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS star_transactions_participant_only ON star_transactions;
CREATE POLICY star_transactions_participant_only ON star_transactions
    USING (current_setting('app.current_user_id', true) IS NULL
        OR sender_id = current_setting('app.current_user_id', true)::uuid
        OR receiver_id = current_setting('app.current_user_id', true)::uuid)
    WITH CHECK (current_setting('app.current_user_id', true) IS NULL
        OR sender_id = current_setting('app.current_user_id', true)::uuid
        OR receiver_id = current_setting('app.current_user_id', true)::uuid);

ALTER TABLE star_goals ENABLE ROW LEVEL SECURITY;
ALTER TABLE star_goals FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS star_goals_owner_only ON star_goals;
CREATE POLICY star_goals_owner_only ON star_goals
    USING (current_setting('app.current_user_id', true) IS NULL
        OR user_id = current_setting('app.current_user_id', true)::uuid)
    WITH CHECK (current_setting('app.current_user_id', true) IS NULL
        OR user_id = current_setting('app.current_user_id', true)::uuid);

-- ===================== Paid creator subscriptions =====================
-- Plans: publicly readable (fans must see a creator's active plans); only the
-- owning creator may write. App sessions are further restricted to active rows;
-- privileged server connections (no session user) read everything for analytics.
ALTER TABLE subscription_plans ENABLE ROW LEVEL SECURITY;
ALTER TABLE subscription_plans FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS subscription_plans_read_active_or_owner ON subscription_plans;
CREATE POLICY subscription_plans_read_active_or_owner ON subscription_plans
    FOR SELECT USING (
        current_setting('app.current_user_id', true) IS NULL
        OR is_active = true
        OR creator_id = current_setting('app.current_user_id', true)::uuid
    );
DROP POLICY IF EXISTS subscription_plans_owner_write ON subscription_plans;
CREATE POLICY subscription_plans_owner_write ON subscription_plans
    FOR ALL USING (creator_id = current_setting('app.current_user_id', true)::uuid)
    WITH CHECK (creator_id = current_setting('app.current_user_id', true)::uuid);

-- Paid subscriptions: only the two parties (and privileged server reads) can see a row.
ALTER TABLE paid_subscriptions ENABLE ROW LEVEL SECURITY;
ALTER TABLE paid_subscriptions FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS paid_subscriptions_parties ON paid_subscriptions;
CREATE POLICY paid_subscriptions_parties ON paid_subscriptions
    USING (current_setting('app.current_user_id', true) IS NULL
        OR subscriber_id = current_setting('app.current_user_id', true)::uuid
        OR creator_id = current_setting('app.current_user_id', true)::uuid)
    WITH CHECK (current_setting('app.current_user_id', true) IS NULL
        OR subscriber_id = current_setting('app.current_user_id', true)::uuid
        OR creator_id = current_setting('app.current_user_id', true)::uuid);

-- Subscription payments: strictly the paying subscriber (plus privileged server
-- settlement paths). Creators only ever see aggregates computed server-side.
ALTER TABLE subscription_payments ENABLE ROW LEVEL SECURITY;
ALTER TABLE subscription_payments FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS subscription_payments_parties ON subscription_payments;
CREATE POLICY subscription_payments_parties ON subscription_payments
    USING (current_setting('app.current_user_id', true) IS NULL
        OR subscriber_id = current_setting('app.current_user_id', true)::uuid
        OR creator_id = current_setting('app.current_user_id', true)::uuid)
    WITH CHECK (current_setting('app.current_user_id', true) IS NULL
        OR subscriber_id = current_setting('app.current_user_id', true)::uuid
        OR creator_id = current_setting('app.current_user_id', true)::uuid);

-- Refund records are visible only to the payer, receiving creator, or trusted server.
ALTER TABLE subscription_refunds ENABLE ROW LEVEL SECURITY;
ALTER TABLE subscription_refunds FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS subscription_refunds_parties ON subscription_refunds;
CREATE POLICY subscription_refunds_parties ON subscription_refunds
    USING (current_setting('app.current_user_id', true) IS NULL
        OR subscriber_id = current_setting('app.current_user_id', true)::uuid
        OR creator_id = current_setting('app.current_user_id', true)::uuid)
    WITH CHECK (current_setting('app.current_user_id', true) IS NULL
        OR subscriber_id = current_setting('app.current_user_id', true)::uuid
        OR creator_id = current_setting('app.current_user_id', true)::uuid);

-- Wallet: financial rows are strictly owner-visible. Ledger entries are append-only
-- (no UPDATE/DELETE policy granted), payouts/methods owner-only.
ALTER TABLE creator_earnings ENABLE ROW LEVEL SECURITY;
ALTER TABLE creator_earnings FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS creator_earnings_owner_only ON creator_earnings;
CREATE POLICY creator_earnings_owner_only ON creator_earnings
    USING (creator_id = current_setting('app.current_user_id', true)::uuid)
    WITH CHECK (creator_id = current_setting('app.current_user_id', true)::uuid);

ALTER TABLE wallet_ledger_entries ENABLE ROW LEVEL SECURITY;
ALTER TABLE wallet_ledger_entries FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS wallet_ledger_owner_only ON wallet_ledger_entries;
CREATE POLICY wallet_ledger_owner_only ON wallet_ledger_entries
    USING (user_id = current_setting('app.current_user_id', true)::uuid)
    WITH CHECK (user_id = current_setting('app.current_user_id', true)::uuid);

ALTER TABLE payout_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE payout_requests FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS payout_requests_owner_only ON payout_requests;
CREATE POLICY payout_requests_owner_only ON payout_requests
    USING (user_id = current_setting('app.current_user_id', true)::uuid)
    WITH CHECK (user_id = current_setting('app.current_user_id', true)::uuid);

ALTER TABLE payout_methods ENABLE ROW LEVEL SECURITY;
ALTER TABLE payout_methods FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS payout_methods_owner_only ON payout_methods;
CREATE POLICY payout_methods_owner_only ON payout_methods
    USING (user_id = current_setting('app.current_user_id', true)::uuid)
    WITH CHECK (user_id = current_setting('app.current_user_id', true)::uuid);

ALTER TABLE payout_method_changes ENABLE ROW LEVEL SECURITY;
ALTER TABLE payout_method_changes FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS payout_method_changes_owner_only ON payout_method_changes;
CREATE POLICY payout_method_changes_owner_only ON payout_method_changes
    USING (user_id = current_setting('app.current_user_id', true)::uuid)
    WITH CHECK (user_id = current_setting('app.current_user_id', true)::uuid);

ALTER TABLE wallet_idempotency ENABLE ROW LEVEL SECURITY;
ALTER TABLE wallet_idempotency FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS wallet_idempotency_owner_only ON wallet_idempotency;
CREATE POLICY wallet_idempotency_owner_only ON wallet_idempotency
    USING (user_id = current_setting('app.current_user_id', true)::uuid)
    WITH CHECK (user_id = current_setting('app.current_user_id', true)::uuid);

ALTER TABLE wallet_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE wallet_events FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS wallet_events_owner_only ON wallet_events;
CREATE POLICY wallet_events_owner_only ON wallet_events
    USING (user_id = current_setting('app.current_user_id', true)::uuid)
    WITH CHECK (user_id = current_setting('app.current_user_id', true)::uuid);

-- Stars wallet, ledger and purchases: strictly owner-scoped. SponsoredEvents writes
-- carry the viewer's own id, so they are owner-scoped too (viewer = acting user).
ALTER TABLE star_wallets ENABLE ROW LEVEL SECURITY;
ALTER TABLE star_wallets FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS star_wallets_owner_only ON star_wallets;
CREATE POLICY star_wallets_owner_only ON star_wallets
    USING (user_id = current_setting('app.current_user_id', true)::uuid)
    WITH CHECK (user_id = current_setting('app.current_user_id', true)::uuid);

ALTER TABLE star_ledger_entries ENABLE ROW LEVEL SECURITY;
ALTER TABLE star_ledger_entries FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS star_ledger_entries_owner_only ON star_ledger_entries;
CREATE POLICY star_ledger_entries_owner_only ON star_ledger_entries
    USING (user_id = current_setting('app.current_user_id', true)::uuid)
    WITH CHECK (user_id = current_setting('app.current_user_id', true)::uuid);

ALTER TABLE star_purchases ENABLE ROW LEVEL SECURITY;
ALTER TABLE star_purchases FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS star_purchases_owner_only ON star_purchases;
CREATE POLICY star_purchases_owner_only ON star_purchases
    USING (user_id = current_setting('app.current_user_id', true)::uuid)
    WITH CHECK (user_id = current_setting('app.current_user_id', true)::uuid);

ALTER TABLE campaigns ENABLE ROW LEVEL SECURITY;
ALTER TABLE campaigns FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS campaigns_owner_only ON campaigns;
CREATE POLICY campaigns_owner_only ON campaigns
    USING (owner_id = current_setting('app.current_user_id', true)::uuid)
    WITH CHECK (owner_id = current_setting('app.current_user_id', true)::uuid);

ALTER TABLE video_series ENABLE ROW LEVEL SECURITY;
ALTER TABLE video_series FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS video_series_owner_only ON video_series;
CREATE POLICY video_series_owner_only ON video_series
    USING (owner_id = current_setting('app.current_user_id', true)::uuid)
    WITH CHECK (owner_id = current_setting('app.current_user_id', true)::uuid);

-- Series items reference series owned by the acting user.
ALTER TABLE series_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE series_items FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS series_items_owner_only ON series_items;
CREATE POLICY series_items_owner_only ON series_items
    USING (series_id IN (SELECT id FROM video_series WHERE owner_id = current_setting('app.current_user_id', true)::uuid))
    WITH CHECK (series_id IN (SELECT id FROM video_series WHERE owner_id = current_setting('app.current_user_id', true)::uuid));

-- Sponsored events are written by the viewing user; reads stay privileged-only.
ALTER TABLE sponsored_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE sponsored_events FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS sponsored_events_viewer_insert ON sponsored_events;
CREATE POLICY sponsored_events_viewer_insert ON sponsored_events
    FOR INSERT WITH CHECK (viewer_id = current_setting('app.current_user_id', true)::uuid);
DROP POLICY IF EXISTS sponsored_events_privileged_read ON sponsored_events;
CREATE POLICY sponsored_events_privileged_read ON sponsored_events
    FOR SELECT USING (current_setting('app.current_user_id', true) IS NULL);

-- Delivery proofs are created by the server after a sponsored item is selected for
-- a viewer. They are never client-supplied; the viewer may only insert their own row.
ALTER TABLE sponsored_deliveries ENABLE ROW LEVEL SECURITY;
ALTER TABLE sponsored_deliveries FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS sponsored_deliveries_viewer_insert ON sponsored_deliveries;
CREATE POLICY sponsored_deliveries_viewer_insert ON sponsored_deliveries
    FOR INSERT WITH CHECK (viewer_id = current_setting('app.current_user_id', true)::uuid);
DROP POLICY IF EXISTS sponsored_deliveries_privileged_read ON sponsored_deliveries;
CREATE POLICY sponsored_deliveries_privileged_read ON sponsored_deliveries
    FOR SELECT USING (current_setting('app.current_user_id', true) IS NULL);

-- Comments mirror the feed engagement model: reads are privileged-only (the server
-- computes pages and counts after applying post visibility in SQL); writes are
-- scoped to the acting user (author / liker / reporter / gift sender).
ALTER TABLE post_comments ENABLE ROW LEVEL SECURITY;
ALTER TABLE post_comments FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS post_comments_server_read ON post_comments;
CREATE POLICY post_comments_server_read ON post_comments
    FOR SELECT USING (current_setting('app.current_user_id', true) IS NULL OR author_id = current_setting('app.current_user_id', true)::uuid);
DROP POLICY IF EXISTS post_comments_author_write ON post_comments;
CREATE POLICY post_comments_author_write ON post_comments
    FOR INSERT WITH CHECK (author_id = current_setting('app.current_user_id', true)::uuid);

ALTER TABLE comment_likes ENABLE ROW LEVEL SECURITY;
ALTER TABLE comment_likes FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS comment_likes_owner_only ON comment_likes;
CREATE POLICY comment_likes_owner_only ON comment_likes
    USING (current_setting('app.current_user_id', true) IS NULL OR user_id = current_setting('app.current_user_id', true)::uuid)
    WITH CHECK (user_id = current_setting('app.current_user_id', true)::uuid);

ALTER TABLE comment_reports ENABLE ROW LEVEL SECURITY;
ALTER TABLE comment_reports FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS comment_reports_reporter_only ON comment_reports;
CREATE POLICY comment_reports_reporter_only ON comment_reports
    USING (current_setting('app.current_user_id', true) IS NULL OR reporter_id = current_setting('app.current_user_id', true)::uuid)
    WITH CHECK (reporter_id = current_setting('app.current_user_id', true)::uuid);

ALTER TABLE comment_star_gifts ENABLE ROW LEVEL SECURITY;
ALTER TABLE comment_star_gifts FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS comment_star_gifts_party_only ON comment_star_gifts;
CREATE POLICY comment_star_gifts_party_only ON comment_star_gifts
    USING (current_setting('app.current_user_id', true) IS NULL
        OR sender_id = current_setting('app.current_user_id', true)::uuid
        OR recipient_id = current_setting('app.current_user_id', true)::uuid)
    WITH CHECK (sender_id = current_setting('app.current_user_id', true)::uuid);
