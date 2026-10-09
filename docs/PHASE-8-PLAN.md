# Phase 8 — Codex phone Direct quota experiment

## Objective

Allow a phone to refresh Codex usage while the paired computer and its Bridge are
offline. The experiment is opt-in per Codex account, stores an independent phone
OAuth credential, and keeps the existing account id, Bridge credential, history,
and widget bindings.

The Codex device-code flow and `/backend-api/wham/usage` endpoint are internal,
undocumented interfaces. This phase is a personal-device experiment, not a
stability or public-support promise. A browser may reuse its own OpenAI session;
the app never reads credentials from the ChatGPT app.

## Implemented in this phase

1. **Device authorization and token ownership**
   - Requests a Codex device code, shows the code, opens the official device page,
     honors the server polling interval, treats 403/404 as pending, waits on 429,
     and stops on cancellation or after 15 minutes.
   - Exchanges the one-time authorization code once, without retrying it.
   - Refreshes OAuth tokens before expiry and stores rotated tokens. Revoked or
     rejected authorization is marked for user action and is not retried in a loop.
   - OAuth credentials require Android Keystore protection. The existing degraded
     storage path remains available for older credential types, but OAuth writes
     fail closed.

2. **One Codex card, two complete sources**
   - A new Direct-only Codex card may be created, or Direct may be manually
     associated with an existing Bridge card.
   - Before association, the confirmation shows the Direct email/workspace claim
     and Bridge card name. It states that this is a user association and identity
     is not verified; changed claims require confirmation again.
   - A successful Direct result is preferred. On Direct failure, the refresh chain
     requests and stores the complete Bridge result. It never merges fields across
     sources. If both sources fail, the prior successful snapshot remains visible.
   - The card and detail view show the actual snapshot source and its timestamp.
     Missing quota fields remain unknown; window duration and reset time are not
     guessed.

3. **Persistence, scheduling, and duplicate suppression**
   - Database schema v5 adds optional Direct credential, opt-in, identity
     fingerprint, and reauthorization state columns. Existing account ids and
     history rows are not rewritten.
   - Opening the account list refreshes enabled accounts. Concurrent app, widget,
     and alarm requests for one account share one in-flight refresh.
   - Background refresh defaults to a best-effort 15-minute alarm. With widgets,
     it refreshes enabled accounts; without widgets, it refreshes only opted-in
     Direct accounts. Boot recovery uses the same scheduler. Android may delay an
     idle alarm.
   - Disabling or unlinking Direct leaves the Bridge credential intact. Unlinking
     Direct from a standalone card removes only that card's OAuth credential.

## Entry points

- Account editor and device-code screen:
  `app/src/main/java/com/aiusage/monitor/ui/account/AccountEditActivity.java`,
  `CodexDeviceAuthActivity.java`
- Device OAuth and secure credential path:
  `app/src/main/java/com/aiusage/monitor/auth/CodexOAuthClient.java`,
  `OAuthAuthAdapter.java`, `storage/SqliteCredentialStore.java`
- Direct quota request and parsing:
  `provider/codex/CodexDirectDataSource.java`,
  `CodexDirectUsageParser.java`, `CodexProvider.java`
- Direct-first and Bridge fallback:
  `refresh/AccountRefreshManager.java`
- Persistence and scheduling:
  `storage/Database.java`, `SqliteAccountRepository.java`,
  `widget/WidgetRefreshScheduler.java`, `WidgetRefreshReceiver.java`

## Acceptance status

Automated coverage includes OAuth device-code polling and one-time exchange,
`Retry-After` seconds/date handling, OAuth payload safety, Keystore-only storage,
flexible quota-window parsing, Bridge fallback routing generation, and no-widget
Direct scheduling and interrupted-waiter refresh de-duplication.
`:app:testDebugUnitTest` completed with 579 tests, 0 failures,
0 errors, and 1 skipped test. `:app:assembleDebug` succeeded; the debug APK is at
`app/build/outputs/apk/debug/app-debug.apk`.

The emulator smoke test now confirms that the isolated Debug package starts and
opens the account editor. The Codex Direct option is visible and unchecked by
default. No authorization was started.

The following still require an authorized personal account and, for power-off
and reboot scenarios, a physical Android phone:

- The existing `com.aiusage.monitor` 4.0.0 install has a different signing
  certificate and remains untouched. Debug 4.1.0 is installed alongside it as
  `com.aiusage.monitor.debug`.
- Browser session already signed in and signed out; device-code login disabled;
  cancellation; expiry; rate limiting; and reauthorization after revocation.
- A newly returned quota response while the computer is powered off. A cached
  read or reset countdown alone is not evidence of success.
- App restart, phone restart, token rotation, and recovery after Android Keystore
  is temporarily unavailable.
- Full Bridge snapshot fallback, both sources failing, no-widget background
  refresh, and confirmation that visible source and timestamp match stored data.

GraphFlow planning/index synchronization was attempted during implementation but
the MCP returned `Transport closed`; no successful index result is claimed.
