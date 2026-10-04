# Self-Healing: Find & Check Fix, then Review & Apply

The panel offers two actions: **Find & Check Fix** combines read-only preview and isolated validation; **Review & Apply** requires approval of the exact validated finder edits. The external Flutter Test Healer CLI remains the repair engine; the plugin never directly executes a patch or rewrites test source.

## Usage

1. Build/install the plugin or start its IDE sandbox with `./gradlew runIde`.
2. Open **View → Tool Windows → Flutter Self-Healing**, or use **Find Action** (macOS: ⌘⇧A) and search for **Flutter Self-Healing**. Use the compatible Android Studio 2023.1 sandbox; the current build targets platform 231, not Android Studio 2026. Restart the sandbox after rebuilding if its old plugin cannot unload.
3. Expand **Edit setup** and configure absolute directories (setup opens automatically when paths are missing):
   - **Target project**: a Git-root Flutter checkout or Git worktree containing `pubspec.yaml`.
   - **Healer directory**: the checkout containing `bin/flutter_test_healer.dart` and an already prepared `.dart_tool/package_config.json`.
   - **Flutter SDK**: the SDK root, not its `bin/flutter` executable. Its cached bundled Dart SDK must already exist.
4. Save target-project editor changes yourself. The panel refuses known unsaved buffers under the target and never auto-saves them.
5. Click **1. Find & Check Fix** to start immediately after input checks. There is no upfront confirmation or CLI/SDK trust checkbox. If a supported repair is found, selected project tests run automatically in disposable copies. Paths are remembered locally; **Use these paths** can also save and collapse setup. The target stays visible.
6. The plugin generates a preview and **automatically validates that exact proposal** if its status is `requires_review`. No second button or intermediate confirmation is required. Blocked/no-change previews stop without running tests or creating validation workspaces. An already repaired pilot may correctly stop with no old-key references; do not undo the repair.
7. **Check results** and **Finder edits** remain the only two tabs. Progress moves from finding a fix to checking selected tests. Raw JSON and technical-detail controls are not displayed. **Cancel** stops the combined check; apply cannot be cancelled. Editing configuration clears eligibility and cancels any in-progress check; superseded callbacks cannot select a stale result.
8. A completed check shows **Ready to review** only after successful matching validation. **Check results** lists failed test names, file/line locations, linked proposed key changes, and each recorded outcome after the proposed fix. **Finder edits** contains the complete exact patch, including edits not directly linked to an individual failure. Baseline hashes, dependency provenance, raw failure JSON and stage logs are not displayed in successful check summaries. Failed/incomplete checks still show their reason and necessary input-preservation/cleanup warnings with a report location for investigation. Missing failure records are not presented as zero failures or passing tests. No repair has been applied yet. Retrying Find & Check Fix generates fresh evidence.
9. Click **2. Review & Apply** when enabled. The dialog shows the target, edit count, and exact read-only diff. Click **Approve & Apply** to explicitly approve those edits; no checkbox is required. Opening the dialog alone does not approve or apply anything. Cancel or close the dialog to decline. Technical evidence remains in the private reports rather than in the dialog. Declining does not start apply or write an approval artifact; all validation, stale-input and guarded-apply checks remain unchanged.
10. **Apply results** shows selected test results, backups, failure reason, rollback conflicts, and any lock cleanup warning. Refreshing proposed files never auto-saves editor buffers. No approval is reused after an attempt; use Find & Check Fix again for another attempt.

Paths are stored locally per IDE project in workspace settings, not a shared project configuration. The configured CLI, SDK, and project tests run with normal user permissions; only use trusted tools and project code. Clicking Find & Check Fix starts checking, not approval to apply. Exact-edit approval remains a separate step. Preview output stays in memory until automatic validation saves it. Raw reports, diffs, and logs may contain proprietary source and application output. Do not publish them.

Preview calls the selected SDK's Dart executable directly with the healer package configuration and script, followed by `preview --project <canonical-path> --base HEAD --json`. It does **not** use `dart run` or invoke `pub get`. Prepare missing healer dependencies and SDK caches separately. Preview does not execute target tests.

### Shared key constants

The adapter supports literal previews (schema 1) and shared-key previews (schema 2).
Shared previews require one supported `shared_key_value` or `shared_key_symbol`
candidate with consistent finder references, edit spans and file fingerprints.
Unknown schemas and candidate kinds are rejected. The CLI still independently
resolves the proposal; the plugin never applies the patch itself.

Value-only repairs use runtime failure validation. Member-name repairs accept
`before_compile_failed_after_passed` only with exact compiler errors at every
proposed member edit, complete command evidence, matching passing baseline/after
test inventories, and all normal preview-binding, preservation and cleanup checks.
Compile results show test-file locations and proposed member names, not invented
runtime failure names. Both success modes use the same explicit approval and
guarded CLI apply path. Target dependencies must already be prepared; multiple
shared changes and helper tracing remain outside the CLI's initial scope.

### Independent literal-key batches

Schema 3 supports multiple independent literal renames in one or several
production files. The plugin checks the complete source inventory, unique
non-overlapping finder edits, and independent old/new mappings. All edits to a
test file appear in one diff. A successful validation must contain an attributed
failing-then-passing test for every rename, bound to the exact selected preview.
Partial coverage never enables Apply. The existing two-action UI is unchanged:
one combined check and one explicit approval for the whole batch, with the CLI
owning writes, rollback and conflict preservation. Mixed shared/literal batches,
rename chains/swaps, and helper tracing remain unsupported.

## Automatic validation and private reports

For a reviewable preview, the combined check saves the **exact preview JSON backing the proposal**, without reserialization, to a new `~/.flutter-test-healer/runs/plugin-validation-*/preview.json`. The root/request directories must be private (0700); plugin-written files are 0600. Paths inside the target, symlink aliases, and non-POSIX filesystems are refused. An existing non-private root is rejected rather than silently changing its permissions.

The CLI is invoked directly with `validate --project ... --report ... --flutter <sdk>/bin/flutter --timeout 180 --artifacts-dir <request-directory> --json`. No new preview is silently substituted. The CLI independently checks AST-limited edits and stale source/test inputs before execution.

Validation performs offline locked `pub get` and selected baseline/before/after tests **in disposable file copies**, with `--no-pub` for tests. No target-checkout dependency setup or repair is performed. SDK/Pub caches are shared; project tests run with normal permissions and network access. This is not a security sandbox.

The CLI stores its own `validate-*/result.json`, copied preview, stage logs, and JSONL evidence within the request directory. The plugin requires the returned report to match the persisted result, the canonical project, the exact preview bytes and SHA-256 binding, and exit/status semantics. Success also requires baseline/failure attribution, complete unskipped counts, original-input preservation, and workspace cleanup. Missing or contradictory results are never treated as successful validation. Failed/skipped baseline or incomplete evidence remains visible as such, not a passing repair.

The compact summary is presentation-only. Full provenance, counts, stage logs and source fingerprints remain in the private reports, and all validation/apply eligibility checks are unchanged. Formatter tests cover concise failed-test/edit rendering, missing evidence, unsuccessful checks, recovery warnings, and unchanged raw evidence/patches.

Check results lists **all proposed finder edits** by test file and line, separately from recorded before-failures and their after-outcomes. An unrelated key is still listed when it has no linked failure record. This inventory is not evidence that an edit fixes a failure: a failed check or incomplete batch coverage still blocks Apply. Finder edits remains the complete, unchanged patch.

Per-stage timeout is 180 seconds; the plugin applies a 25-minute wall-clock limit (seven commands plus snapshot/cleanup overhead) and a 16 MiB limit per output stream. Progress reports the latest stage log observed, which may remain visible during cleanup.

**Cancellation/timeout:** process-tree cleanup is best effort. The CLI can be terminated before its `finally` cleanup, leaving source snapshots or partial reports. The plugin preserves the private directory and writes an incomplete/cancelled outcome instead of accepting a partial result. The combined workflow also checks cancellation/unsaved inputs before preview, at the validation handoff, and after validation. If interrupted after a request is created, it attempts to record `check-outcome.txt`, including when interruption happens outside the validator call. Inspect the displayed request directory, ensure subprocesses have stopped, and review/remove leftover `workspace/` or `snapshot/` copies manually. No automatic source-copy deletion is attempted after interruption. Use Find & Check Fix again to retry.

Configuration changes clear the selected preview, validation, and apply eligibility. Saved source drift is rejected by the CLI; unsaved buffers in the target block launching validation/apply. Results describe an earlier saved-file snapshot and must not be reused after further input changes. Reports are retained for review; no automatic retention cleanup is implemented.

## Approve & Apply

**Prepare target dependencies separately before preview/validation.** Apply never runs `pub get`, upgrades packages, or generates source. It runs selected Flutter tests with `--no-pub` directly in the chosen checkout, not a disposable copy. Do not substitute the main checkout for a validated disposable target. The command itself rejects a missing package configuration and changed validated inputs.

After explicit approval, the plugin rechecks the selected persisted validation, saves exact preview/validation payloads and approval metadata in a private `plugin-apply-*` request, and calls the existing CLI `apply ... --approve --json`. The CLI independently rechecks AST-limited edits, preview binding, validation/failure evidence, full copied-input fingerprints, and the selected Flutter command. Existing reports are not silently regenerated or replaced. Approval metadata is local audit evidence, not a signature or a security boundary.

**Shared lock root:** every plugin apply passes `--artifacts-dir ~/.flutter-test-healer/runs`, not its unique request directory or validation directory. Consequently all default-root CLI/plugin operations cooperate through the same per-project `apply-<project-digest>.lock`. CLI journals, backups, logs, and results are in a new `apply-*` child there. Manual commands using a different artifact root will not share this lock; use the same root. Editors never honor the tool's lock.

| Apply result | Meaning |
| --- | --- |
| `applied_and_tests_passed` | Finder edits installed and the same selected tests passed; not full-suite verification. |
| `already_applied` | Exact repair already present; no new writes or test run. |
| `blocked` / `approval_required` | No accepted application; inspect prerequisites, stale inputs, or approval. |
| `application_failed` | Process/filesystem preparation failed; inspect the report. |
| `rolled_back` | Application not accepted; tool-owned edits restored. Inspect `failureReason`. |
| `rollback_conflict` | Unexpected content preserved rather than overwritten; review each conflict and its backup manually. |

The returned JSON must match the persisted new report, exact approved input payloads/digests, project/baseline, and exit/status semantics. Accepted writes require complete passing unskipped post-apply tests. Transient journal states (`prepared`, `applying`, `testing`), malformed reports, missing finalization, and inconsistent results are never shown as successful application.

**Do not interrupt apply.** Its task and Cancel button are non-cancellable so the CLI can finish rollback and lock cleanup. Closing the tool window does not intentionally cancel the command. Do not close the IDE or edit target files during the operation. Tests have a 180-second limit; the wrapper has a 10-minute overall emergency limit and 16 MiB output limits. A hard interruption/timeout/IDE shutdown can bypass CLI cleanup. An unverified outcome means files **may have changed**, not that rollback succeeded. The request records the shared root and project lock path; inspect the running processes, journal, and backups before manually recovering or removing any lock. The plugin never steals locks, deletes recovery backups, or overwrites independent edits.

No staging, commits, pushes, or dependency setup are requested by apply. Tests are trusted code with normal local-user permissions and may themselves have side effects outside the proposed files; this is not a security sandbox.

## Status semantics

- `requires_review`: a proposal exists; tests have **not** been run, and the proposal has not been approved.
- `blocked`: unsupported/ambiguous input or analysis warnings; no patch is offered.
- `no_changes`: no supported proposal; this is **not** evidence that tests pass or that all tests are unaffected.
- Process errors, malformed/unsupported reports, contradictory exit/status values, timeout, cancellation, or output overflow: no report accepted.

Preview schema version, canonical target, resolved baseline, read-only flags, status, exit code, warnings, and basic patch metadata are checked. The plugin never interprets or executes the diff. AST, fingerprint, and exact validation-binding enforcement remain in the CLI.

A report describes saved files at preview time, not live editor state. Regenerate after any source/test changes. Unsaved edits in other IDE instances cannot be detected. The CLI process has normal local-user permissions and inherited environment: this is **not a sandbox**. Only run a trusted CLI and SDK. Process-tree termination is best effort, with a 60-second overall preview timeout and a 4 MiB limit per output stream.

## Existing functionality

The previous key-change and widget-tree dialogs remain unchanged and separate. Their legacy regex-based updates are **not** the guarded healer workflow. This panel does not trigger those dialogs or reuse their write helpers. No claims about their safety are added by this integration.

Broader SMC failures remain deferred; neither a preview nor selected-test validation represents full-suite success.

## Verification

`./gradlew test buildPlugin --console=plain` runs isolated command/report/process tests and builds the ZIP. Tests cover explicit argument handling (including spaces and shell metacharacters), missing prerequisites, report states and contradictions, malformed JSON, output limits, cancellation, timeout, and child-process cleanup. Default tests do not create Git commits; the opt-in shared-key smoke tests commit only their own disposable fixtures.

Validation tests also cover eligibility, byte-preserving private storage, unsafe artifact paths/permissions, exact command binding, early blocked reports, success evidence, mismatched persisted reports and preview digests, and cancellation journaling.

Presentation tests cover the fixed two-tab view at every stage, absence of extra technical controls, automatic stage-result selection, preserved recovery warnings/diff text, and read-only fields. These tests do not launch the CLI or execute target tests.

Combined-workflow tests use injected adapters to verify exact preview-byte preservation, automatic single validation, blocked/no-change short-circuiting, malformed previews, cancellation and unsaved-input checks before/between/after phases, unsuccessful validation eligibility, and recovery journaling. Real report integrity remains covered by the existing CLI adapter tests; synthetic orchestration results are not evidence that Flutter tests ran.

An optional real CLI smoke test runs only when all three environment variables are supplied: `HEALER_SMOKE_PROJECT`, `HEALER_SMOKE_ROOT`, and `HEALER_SMOKE_FLUTTER`. It runs preview only, using the same adapter as the UI, and does not set up dependencies or execute Flutter tests.

Set `HEALER_SHARED_SMOKE=1`, `HEALER_SMOKE_ROOT` to the healer checkout, and
`HEALER_SMOKE_FLUTTER` to the SDK directory to run `SharedKeyCliSmokeTest`.
These two tests create fresh temporary Git repositories, prepare dependencies
offline there, and exercise the actual combined check, explicit approval,
guarded apply and idempotent reapplication for value and member-name changes.
They never accept a developer checkout as their target. Use `--rerun-tasks`
when changing opt-in environment variables so Gradle does not reuse old results.

`HEALER_BATCH_SMOKE=1` with the same healer/SDK variables enables
`LiteralBatchCliSmokeTest`, which creates its own temporary Flutter repository
and checks a schema-3 batch through the real plugin validation and guarded apply
adapters. Its dependency setup and fixture commit are confined to that temporary
repository; no application checkout is used.

The separate `HEALER_VALIDATION_SMOKE_PROJECT` opt-in uses the same healer/SDK variables and **does execute trusted Flutter tests** through isolated validation. It requires a disposable clone whose parent directory starts with `plugin-validation-smoke.`, containing a supported key rename, unrepaired baseline tests, and existing lockfiles. It also temporarily changes source in that clone to verify stale rejection, restoring it afterward. Never point it at a developer checkout. Reports persist under the same private artifact root as UI validation.

Apply unit tests cover explicit approval, matching evidence, immutable request payloads, shared lock-root selection, terminal statuses, post-test evidence, stale/changed reports, rollback/conflict presentation, and uncertain-outcome recording without lock deletion. The opt-in `HEALER_APPLY_SMOKE_PROJECT` requires a disposable clone under a `plugin-apply-smoke.*` parent and uses the same healer/SDK variables. Prepare its dependencies separately before running tests. It validates, verifies stale-source rejection, applies the actual repair, and checks idempotence. On success it restores only the known fixture test bytes after checking the installed hashes, leaving an unrepaired example for interactive testing. It never targets the normal checkout or repaired pilot. The CLI's own guarded-apply tests separately exercise rollback and conflict preservation.

IDE interaction (browsing paths, reopen/persistence, tool-window disposal, and visual layout) should also be checked in the sandbox; unit tests do not substitute for that manual UI check.

## Next milestone

Review the combined flow in the compatible sandbox using a disposable unrepaired fixture: one Find & Check Fix action should start without a dialog and progress to validation without another click, and only a successful check should enable Review & Apply. Also verify cancelling mid-check, blocked previews, and approval-dialog cancellation. Do not reset the repaired pilot. Keep supported repair scope narrow and broader suite failures deferred; do not introduce unattended approval or generic assertion rewrites.

