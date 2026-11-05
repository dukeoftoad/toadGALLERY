---
name: Bug report
about: Something broke or behaved wrongly --- tell us what, where, and how
title: "[bug] "
labels: bug
assignees: ""
---

Thank you for taking the time to report --- a heads-up before the form:
toadGALLERY is a **pre-release** project and its docs list
[known limitations](https://github.com/dukeoftoad/toadGALLERY/blob/main/docs/phase-11/known-limitations.md)
(open follow-ups, deferred scope). It's worth a quick check whether what
you hit is already on that honest list.

**Which app?**

- [ ] toadGALLERY (the gallery app)
- [ ] toadGALLERY Edit (the editor app)
- [ ] Both / the hand-off between them

**Version**

- Gallery: Settings → About shows it as `<versionName> (build <versionCode>)` --- please paste it.
- Editor: Android Settings → Apps → toadGALLERY Edit → the version line.
- Or: the release you downloaded (e.g. `v0.4.5-alpha-r45`).

**Device and Android version**

<!-- e.g. "Pixel 8a, Android 15" --- the API ladder starts at Android 8.0. -->

**Steps to reproduce**

1.
2.
3.

**Expected behavior**

**Actual behavior**

<!-- Crash? Attach the logcat snippet below --- the raw stack trace matters
     more than a screenshot of the "app stopped" dialog. -->

**Logcat / stack trace (if it crashed)**

```
<paste here>
```

A note on stack traces: the pre-release APKs are **R8-minified**, so a
crash trace arrives obfuscated. Attach it anyway --- every release ships
its `*-mapping.txt` as a release artifact, so maintainers can symbolicate
your trace against the exact release you installed. If you can, also say
which release you're on.

A note on privacy (why nothing was reported automatically): toadGALLERY
collects **nothing** --- no analytics, no crash reporting, no telemetry.
The gallery's only network use is downloading AI models you explicitly
enable, from a single pinned host; the editor has no network capability
at all. So the bug can't phone home --- you are the reporting mechanism,
and we'd rather keep it that way.
