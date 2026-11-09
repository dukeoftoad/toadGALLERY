# r50 (the identity/audit sweep): the stale org.fossify keep rule is
# removed --- the org.fossify:commons dependency was dropped per ADR-0008
# and a keep rule for an absent dependency is a no-op that only rots.
# toadGALLERY Edit R8 rules (r43, the Phase 11 release-engineering round).
#
# This file exists because the release variant of the edit APK is minified
# for the first time in r43 (isMinifyEnabled + isShrinkResources in
# apps/edit/build.gradle.kts, mirroring the gallery's release build type),
# so the release ships a mapping.txt for crash symbolication like the
# gallery does. It stays minimal ON PURPOSE: media3, Compose and the
# AndroidX libraries ship their own consumer rules inside their AARs, so a
# long hand-written keep list here would be guesswork the first minified
# build has not yet earned --- anything this file did NOT need to keep and
# R8 can safely shrink, it should shrink (the editor's APK size is a
# recorded lean from the r42 round). Catches from the first real release
# runs will be recorded and fixed here per the merge-gate convention; a
# rule lands only with the failure (or the near-miss) that justifies it.

# --- ONNX Runtime (the r41 inference seam) --------------------------------
# The ai.onnxruntime.** classes are the JNI surface the native runtime
# looks up BY NAME from C++ (ai.onnxruntime.OrtEnvironment and friends).
# The prebuilt AAR carries its own consumer proguard rules, so this keep
# is belt-and-braces on top of them: the editor must never crash at
# runtime because R8 stripped or renamed a class the JNI layer references
# reflectively --- the models ship in assets/models/ (the ship-in-APK
# rule, ADR-0010's revision) and the inference path is opt-in surface, so
# a lazy class load at first magic-erase is the exact shape of bug this
# keep exists to make impossible.
-keep class ai.onnxruntime.** { *; }

# --- Referenced-but-absent shims inside the ORT AAR -----------------------
# The onnxruntime-android AAR references these annotation/logging APIs in
# its bytecode without depending on them: org.checkerframework's
# nullness qualifiers and org.slf4j's logger facade are compile-time-only
# or optional companions that simply are not on the editor's classpath.
# R8 would emit missing-class warnings for them and (with
# -Werror absent) still succeed, but the warnings bury the real ones ---
# they are documented as harmless here instead of being silenced
# globally with -dontwarn at large.
-dontwarn org.checkerframework.**
-dontwarn org.slf4j.**
