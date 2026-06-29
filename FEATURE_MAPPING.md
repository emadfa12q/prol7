# Feature Mapping v6

## Core

- Usage tracking: implemented with UsageStatsManager and foreground service.
- Realtime updates: service polling and UI refresh run every 1 second.
- Activity screenshots: implemented with AccessibilityService.takeScreenshot when the user enables the accessibility service.
- Reports/PDF: implemented locally.
- Jalali dates: implemented in JalaliUtils.
- Local storage: SQLite.

## UI/UX

- Mobile-first layout with home, activity, reports, and settings.
- Permissions and service controls are in Settings only.
- Safe insets are applied for status bar and navigation bar.
- Bottom navigation stays above phone navigation buttons.
- Activity list uses cards instead of desktop-style tables.

## v6 additions

- PIN gate on launch with code `1183` and `=` to unlock.
- Timeline zoom controls: `+` zooms in, `−` zooms out.
- Timeline zoom levels: 24h, 12h, 6h, 3h.
- Timeline redraws with live data.

## Not implemented intentionally

The app is not disguised as another app. The launcher name remains Work Time Tracker for clarity and consent.
