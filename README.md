# toadGALLERY --- `unified-master` (ARCHIVED)

**This branch is archived. Do not develop here.** It is the pre-Phase-1
unified application line, **frozen at the modularization boundary**: its tree
still carries the old monolithic `apps/` module layout
(`apps/gallery`, `apps/edit`) that the Phase 1 modularization replaced with
the `gallery/` + `editor/` + `unified/` + `engine/` + `shared/` structure
used by every live branch.

| | |
|---|---|
| **Status** | ARCHIVED --- kept for history and forensics only. |
| **Push CI** | none. |
| **Releases** | none --- and its gradle targets (`:apps:*`) no longer exist in the live module layout anyway. |
| **Do not** | open PRs from it, rebase it, or dispatch workflows at it. |

The live branches of the virtual fork are `main` (stable releases),
`unified` (integration + alpha/beta), `gallery`, `editor` (product lanes),
and `ente` (upstream sync lane). Start from the
[`main` README](https://github.com/dukeoftoad/toadGALLERY/blob/main/README.md)
or the
[branch strategy](https://github.com/dukeoftoad/toadGALLERY/blob/main/.github/BRANCH_STRATEGY.md).
