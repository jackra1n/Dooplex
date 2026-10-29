# CI

Every pull request and every push to `main` must pass `.github/workflows/ci.yml`, which runs
`mise run build` (signed with a throwaway debug key). That one build also runs R8 and
`tools/check-dex-access.py`, so it catches the release-only crashes in
[r8-release-builds.md](r8-release-builds.md), not just compile errors.
