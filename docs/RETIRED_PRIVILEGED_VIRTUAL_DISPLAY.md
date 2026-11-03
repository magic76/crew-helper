# Retirement: privileged background virtual display

Removed intentionally on 2026-11-03 because Shell privilege via Shizuku,
wireless debugging or manual ADB could not meet the product requirement of
reliable operation without Wi-Fi or developer-mode setup.

Removed from the shipped App: Shizuku dependency, permission/provider,
Shell UID Binder handoff, virtual display Activity, all privileged
background tools, local ADB launcher, and associated policy/tests.

The normal Android Accessibility/Gemini Live foreground experience is
unchanged. Independent user-facing timers, screen monitors and other
background work that do **not** require ADB remain available.

Safety: requests explicitly targeting a retired virtual/background
display fail closed with VIRTUAL_DISPLAY_REMOVED; they must never execute
on the physical screen without a fresh, explicit foreground request.

Historical implementation is available via GitHub commit history and
PR #186–#200. No claim is made that installing the APK can terminate
an old external Shell process; an old helper started outside the App
must be stopped through the original authorized ADB environment if needed.
