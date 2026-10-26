# R8 release builds

Release builds are shrunk and partly optimized by R8 (Android's code shrinker and optimizer), and
one of its optimizations once made the app crash before drawing anything. Two guards keep that from
coming back; do not remove them.

## The setup

`app/build.gradle.kts` turns on R8 optimization only for library code
(`packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")`). Dooplex's own classes keep
their names, which keeps crash reports readable; library names are renamed, and
`app/build/outputs/mapping/release/mapping.txt` maps them back (used by `tools/trace-report.py`).

## The crash (October 2026)

**Symptom:** the app showed a black screen for about 300 ms and closed, every time. The crash
reporter sent nothing.

**Cause:** `IllegalAccessError: Class kotlin.collections.g extended by class wd is inaccessible`.
Kotlin splits some files into a package-private parent class and parts named `FooKt__Bar`. R8's
package-scoped optimization moved one part into another package while its package-private parent
stayed behind, and ART refuses to load a class whose parent it cannot access. The crash reporter
itself used the broken class (through `sortedBy`), so it died too.

## The guards

- **`app/src/main/keepRules/rules.keep`** keeps the names of Kotlin multifile parts
  (`-keepnames class kotlin.**Kt__*` and the same for `kotlinx`), so R8 cannot move them apart.
- **`tools/check-dex-access.py`** runs in every `mise run build`. It reads the APK's dex files and
  fails the build when any class extends or implements a package-private class from another
  package: exactly the shape ART rejects at launch.
- **The crash reporter installs first**, in `DooplexApplication.attachBaseContext`, and writes the
  stack trace to a file before doing anything else, so even a crash during startup or inside the
  reporter's own code is sent on the next start (see [dev-workflow.md](dev-workflow.md)).

## If it happens again

Build the version from before the suspect change; if that starts, the cause is in the change or in
how R8 treats it. Run `tools/check-dex-access.py` by hand on the bad APK, and look for
`IllegalAccessError` or `VerifyError` in the crash report's log.
