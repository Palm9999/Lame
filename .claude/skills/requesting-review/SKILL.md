---
name: requesting-review
description: Use when substantial or risky work is complete, before merging, or when a second perspective would catch what you might have missed
---

# Requesting Review

A reviewer with fresh context sees what the author has stopped seeing. Review supplements deterministic checks (tests, types, linters) but doesn't replace them: run those first, so the reviewer spends attention on what machines can't judge.

## Scale it

- **Trivial / Small:** no separate review. Read your own diff once before reporting.
- **Substantial:** one review of the whole change.
- **Risky** (security, data, money, public API, hard to undo): review, and verify each serious finding.

## How

1. **Run the deterministic checks first** and fix what they find.
2. **Gather context for the reviewer:** what was meant to be built (spec or task text), what was built, and the change range (commit SHAs or a list of files). Give it only this, not your session history.
3. **Dispatch a reviewer** with `reviewer-prompt.md`. Use a capable model for risky changes. If the harness can't dispatch subagents, do a separate review pass yourself using the prompt as a checklist, reading the diff rather than your memory of it, or ask the user to review.
4. **Verify findings before acting.** For each Critical or Important finding, confirm it against the code: reproduce it, or read the lines cited. Reviewers are sometimes wrong, especially without full context.
5. **Act by severity:**
   - **Critical:** fix before anything else.
   - **Important:** fix before merging, or explain to the user why not.
   - **Minor:** fix if cheap; otherwise list it.
6. **Re-review only what changed**, and only if the fixes were non-trivial. Loops end when checks pass and serious findings are resolved, not when a reviewer says "approved".

## Skip or shorten when

- The user will review the change themselves and would rather do that.
- The change is mechanical (rename, formatting, dependency bump) and the checks pass.

## Done means

Deterministic checks pass, and every verified Critical or Important finding is fixed or explicitly accepted by the user.

## Next

- `receiving-review` for handling the feedback
- `finishing-work`
