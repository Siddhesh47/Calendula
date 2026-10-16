# Project Brief: Extending Etar Calendar (for Android Studio AI)

## Context

We are **not building a calendar app from scratch**. We are forking and extending an existing, working open-source Android calendar app called **Etar Calendar** (`Etar-Group/Etar-Calendar` on GitHub).

- Language: Java (95.5%) + Kotlin (4.5%)
- Build system: Gradle (`gradle :app:assembleDebug`)
- License: GPLv3
- Data layer: Android's built-in **CalendarContract / Calendar Provider API** — NOT a custom database. Etar reads/writes events through the same system calendar storage used by Google Calendar, Exchange, etc.
- Existing features: month/week/day/agenda views, offline calendars, recurring events (daily/weekly/monthly), ICS import/export, Material Design UI, dark/light theme.

**Do not rewrite the app. Work within the existing architecture and coding conventions.** The goal is to add two new capabilities on top of the existing codebase without breaking existing functionality (normal event creation, normal recurrence, sync, etc.).

---

## Phase 0 — Orientation (do this first, before writing any code)

1. Locate and read through the following before making changes:
   - Wherever events are created/edited (typically `EditEventFragment` / `EditEventActivity`)
   - Wherever event details are displayed (`EventInfoFragment` or equivalent)
   - Wherever the app talks to `CalendarContract` — this is the core data read/write layer
   - Any existing recurrence-handling code (for daily/weekly/monthly repeats), since our new feature is a *sibling* concept to this, not a replacement
   - Any existing notification/reminder scheduling code (Etar already has reminders — find and reuse this pattern rather than building notification logic from zero)
2. Report back a short summary of these files/classes before proceeding, so we can confirm the plan fits the real code before implementation starts.

---

## Feature 1: "Repeating-Ask" Event Type

### Behavior
This is a new event type, distinct from normal recurring events (daily/weekly/monthly), which continue to exist unchanged.

- When creating an event, the user can mark it as a **"repeating-ask" event** (needs a clear UI toggle/option alongside the existing recurrence picker — do not replace or remove the existing recurrence options).
- When a repeating-ask event **ends**, the app does NOT automatically create the next occurrence. Instead, it **asks the user** for the next date & time that event will happen.
- This ask is delivered via **both**:
  - A push/system notification
  - An in-app popup (if the app is open)
- If the user doesn't respond, the app **re-asks on a cooldown** — default **10 hours** between asks — until the user provides the next occurrence.
- Once the user provides a date/time, a new event instance is created for that date/time (written back through `CalendarContract`, same as any other event), and the ask-cycle resets for that new instance's end.

### Implementation approach
- Store the "this is a repeating-ask event" flag using Calendar Provider's **extended properties** mechanism (`ExtendedProperties` table / `CalendarContract.ExtendedProperties`, or the event's `CUSTOM_APP_URI` if more appropriate) — do not modify the system Calendar Provider schema itself, and do not store this only in an app-local SQLite table disconnected from the real event, since that would break if the event is viewed/edited outside our new UI.
- Use **WorkManager** (add as a dependency if not already present) to schedule the periodic re-ask check after an event's end time, and to schedule the recurring 10-hour cooldown notification until answered.
- Reuse Etar's existing notification channel/permissions setup if one already exists for reminders; otherwise create a new dedicated notification channel for this feature so users can control it separately in system settings.
- The in-app popup should be a dialog that appears if the app is foregrounded and there's a pending ask; check for pending asks on app resume.
- The 10-hour cooldown value should be a named constant, not a magic number, so it can be changed easily later.

---

## Feature 2: Multi-User Shared Calendars (build this only after Feature 1 works)

### Behavior
- A small group of users (e.g. family/friends) can share a calendar within the app — this is **not** Google Calendar sync, and should work independently of whether the user has a Google account.
- No budget for paid backend services — must use free/self-hostable infrastructure.

### Constraint to flag, don't silently work around
Android's Calendar Provider is fundamentally per-device/per-account — it has no native concept of "share this calendar with another phone" without a sync adapter talking to a real server. Etar already supports CalDAV sync via external apps like DAVx5, which points to the practical path:

- Recommended approach: a self-hosted **CalDAV server** (e.g. Radicale, which is free/open-source and lightweight) that shared-calendar events sync through, using standard CalDAV protocols Etar/Android already understand — rather than inventing a custom sync protocol from scratch.
- Personal (non-shared) events can remain in the local Calendar Provider as they already do.
- **Do not start this phase until Feature 1 is complete and tested** — confirm with the user before beginning backend/sync work, since it involves infrastructure decisions (where to host Radicale, account setup) that need to be discussed first.

---

## Ground rules for the AI while working on this

- Prefer extending existing classes/patterns over introducing new architecture or libraries, unless a genuine gap requires it (e.g. WorkManager for scheduling, if not already present).
- Keep normal (non-repeating-ask) event creation, editing, and display fully working — regression-test this mentally against every change.
- After implementing Feature 1, it should be possible to: create a repeating-ask event, let it end, receive a notification + in-app popup, provide a next date, and see a new event created — all without touching sync/backend code.
- Flag any point where Calendar Provider's API doesn't cleanly support what's being asked, rather than hacking around it in a fragile way.
