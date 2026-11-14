# toadGALLERY Edit R8 rules. Deliberately minimal: media3, Compose and AndroidX ship
# their own consumer rules, so anything R8 can safely shrink it should. A rule lands
# only with the failure that justifies it.

# ONNX Runtime: the native runtime resolves ai.onnxruntime.** by name from JNI.
# Belt-and-braces on top of the AAR's consumer rules — a lazy class load at first
# magic-erase must never fail.
-keep class ai.onnxruntime.** { *; }

# The ORT AAR references these compile-time-only APIs without depending on them;
# documented as harmless here instead of a global -dontwarn.
-dontwarn org.checkerframework.**
-dontwarn org.slf4j.**
