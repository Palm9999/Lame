---
name: handoff
description: 'Write this session''s notes into docs/superpowers/HANDOFF.md so the next session can start after /clear. Invoke with /handoff before /clear.'
disable-model-invocation: true
---

# handoff

Update `docs/superpowers/HANDOFF.md` from this session, commit and push it, then tell the user it is safe to `/clear`.

## Steps

1. **Read** `docs/superpowers/HANDOFF.md` and `git status` / `git log origin/claude/relaxed-hypatia-73hhub..HEAD`. Skim the session for what changed.
2. **Edit in place, don't append a log.** Keep the file's existing sections and keep it short:
   - **Where things stand:** replace "Just built" for work finished this session (what it does, rulings made, facts a future session needs). Fold older "Just built" entries into one line in the built-and-merged list. Update `INGEST_VERSION`, `FORECAST_VERSION` and prefs `formatVersion` if they changed.
   - **Next:** set the one next task. If a task is half done, say exactly where it stopped, which files, and what fails or is left.
   - **Rulings that still bind:** add any new user ruling with its date. Remove ones now obsolete.
   - **Deferred minors:** add new ones under the feature's heading. Delete ones fixed.
   - **Open checks on the phone:** add checks the user must do for new work. Delete ones the user reported done.
   - **Container notes:** add only new environment gotchas.
3. **Prune.** Delete anything done, stale or contradicted. No session narration, no "I did X then Y", no code. Details live in git and the PR.
4. **Uncommitted work:** if source changes are unfinished, say so in "Next". Don't commit broken code to hide it.
5. **Commit and push** the HANDOFF change to the working branch named in `CLAUDE.md` (never a new branch). If the session already has unpushed feature commits, it rides with them. Per `CLAUDE.md`, no docs-only PR: if no PR is open, push without opening one. Retry a failed push up to 4 times with 2/4/8/16 s backoff.
6. **Report** in two lines: what the Next task is, and "Safe to /clear."

## Rules

- Never create a branch.
- Never invent state: if unsure whether something shipped, check `git log` or the PR.
- Don't touch `CLAUDE.md` or other docs.
