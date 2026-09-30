---
name: isolating-workspaces
description: Use when starting work that should be kept apart from the current working tree, such as risky changes, parallel features, or long-running plans
---

# Isolating Workspaces

Isolation lets you experiment without disturbing the user's current state and makes it easy to throw work away. Pick the lightest option that gives enough separation.

## Scale it

| Option | Use when | Needs |
|---|---|---|
| None | Trivial or small changes, or the user is working with you live | Nothing |
| Branch | Substantial work, one line of development at a time | git |
| Worktree | Parallel work, or keeping the main checkout usable | git |
| Copy / scratch dir | No git, or disposable experiments | Disk space |

## How

1. **Respect what exists.** If the project or its instruction file (CLAUDE.md, AGENTS.md, GEMINI.md) names a worktree location or branch convention, use it. Otherwise ask once, offering a project-local `.worktrees/` or a directory outside the repo.
2. **Keep project-local worktrees out of version control.** Check with `git check-ignore`. If the directory isn't ignored, propose adding it to `.gitignore` instead of committing the change yourself.
3. **Create the workspace**, e.g. `git worktree add <path> -b <branch>`, and work from it.
4. **Set up dependencies** using the project's own tooling (lockfile, Makefile, README), not a guessed command.
5. **Record a baseline** by running the test suite if it's reasonably fast. Note any tests that already fail so later failures can be told apart. Pre-existing failures are information; don't treat them as blockers unless they touch your work.
6. **Report** where the workspace is, what branch it's on, and the baseline result.

## Skip or shorten when

- The change is small and reversible. Work in place.
- The directory isn't a git repository. Use a copy only if isolation really matters; otherwise work in place and say so.

## Done means

You and the user know where the work lives and what state the baseline was in.

## Next

- `executing-plans` or `delegating-to-subagents`
- `finishing-work` cleans up the workspace afterwards
