---
name: Support / question
about: Install trouble, verification, vault questions, "how do I ..."
title: "[support] "
labels: question
assignees: ""
---

## Verifying a downloaded APK

Every release ships each APK beside its `.sha256` checksum. Verify the
download before installing:

```bash
sha256sum -c toadGALLERY-edit-0.4.10-edit-alpha-r45.apk.sha256
```

To check that the APK really came from this project's release key
(Android ties updates to the signing identity, so this matters when
sideloading):

```bash
apksigner verify --print-certs toadGALLERY-edit-0.4.10-edit-alpha-r45.apk
```

The `Signer #1 certificate SHA-256 digest` line must read exactly:

```
8a4640e0732e28b84c2fa35ee7a350d104ce97d87df92262cc8439832bd29494
```

One release key covers the editor's releases. (On some
setups `apksigner` lives under the Android SDK's `build-tools/<version>/`
directory.)

## Install notes

- **Android 8.0 or newer.**
- toadGALLERY Edit is a standalone app --- updates install in place over
  earlier toadGALLERY Edit pre-releases (same signing identity).

## Where the docs live

- The [README](https://github.com/dukeoftoad/toadGALLERY/blob/main/README.md)
  --- the product overview, the build/CI notes, the links to everything.
- The [release notes](https://github.com/dukeoftoad/toadGALLERY/blob/main/docs/phase-11/release-notes.md)
  --- what each release contains, the full version history, and the
  honest "what this release does NOT claim" section.
- The [known limitations](https://github.com/dukeoftoad/toadGALLERY/blob/main/docs/phase-11/known-limitations.md)
  --- the open follow-ups and deliberate scope lines.
- The [permissions & privacy doc](https://github.com/dukeoftoad/toadGALLERY/blob/main/docs/phase-11/permissions-and-privacy.md)
  --- every permission either app declares, and why; the data-safety
  answers.
- The [docs tree](https://github.com/dukeoftoad/toadGALLERY/tree/main/docs)
  --- per-phase design and delivery records, if you want to go deep.

**Your question**

<!-- What do you need help with? Include the version, your device, and
     what you've already tried. -->
