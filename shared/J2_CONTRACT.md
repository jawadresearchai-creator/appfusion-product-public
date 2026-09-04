# Activity/cadence shared slice

This implements shared J2 persistence, calendar rules and scheduling reconciliation. It is not installed J2 UI acceptance or proof of native notification delivery. The document-vault database, crypto and accepted J1 workflow are unchanged.

## Behavioral decisions

- Each activity has a title, durable completion count/history and an optional calendar-day cadence (1–3650 days, minute of day). The first due date is creation's local date plus cadence; subsequent dates derive from the latest completion instant. Backfilled completions increment history/count but cannot move the latest completion backward.
- Fixed-zone reminders stay in their selected zone. Follow-device reminders reinterpret the anchor instant in the current device zone and calculate the next local calendar date there. Civil dates, not fixed 24-hour durations, drive daily/weekly behavior.
- A missing DST wall time shifts forward by the gap. An ambiguous fall-back time chooses the earlier instant. These are explicit fixtures using [kotlinx-datetime's conversion semantics](https://kotlinlang.org/api/kotlinx-datetime/kotlinx-datetime/kotlinx.datetime/to-instant.html); the dependency is pinned to 0.7.1.
- One outstanding occurrence per activity; clock changes/reboots/repeated reconciliations do not manufacture new occurrence IDs or repeatedly advance overdue dates. Overdue requests retain FIRE_ONCE_WHEN_AVAILABLE intent. A native transport must persist delivery/action deduplication and honor OS permission/capacity constraints before notification acceptance.
- Completion history, count and latest completion update in one database transaction. Duplicate event IDs for the same activity are first-write-wins; cross-activity reuse is rejected. Stale notification occurrences cannot complete the new occurrence. Repeated notification actions keep their first persisted timestamp.
- A single injected notification transport replaces only the requesting domain's schedules; the activity coordinator cannot clear document reminders. Transport errors propagate rather than being reported as delivered notifications.
- Activity database migration 1→2 leaves existing title/count rows intact. It does not invent completion history or silently enable reminders on legacy records without cadence configuration. The document database schema remains version 3.

## Acceptance boundary

Pure calendar fixtures and real Room/SQLite persistence, restart, concurrency and rollback assertions run on JVM and iOS through the existing Product workflow. New installed Android/iOS J2 views, native delivery/permission probes and boot/timezone platform hooks remain the next slice. No extra hosted workflow, paid service or separate scheduler runtime is introduced.
