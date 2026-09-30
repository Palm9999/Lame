---
name: finishing-work
description: Use when implementation is complete and verified, and the work needs to be integrated, handed off, or cleaned up
---

# Finishing Work

Close the loop: confirm the work holds up, tell the user what they're getting, and integrate it the way they want.

## Scale it

- **Trivial / Small, in place:** a short summary of what changed and how it was verified. Done.
- **Work on a branch or in a worktree:** follow the steps below.

## How

1. **Verify the whole change** with the full test suite, plus build and lint if the project uses them. If something fails, report it and fix it before offering to integrate.
2. **Summarise:** what was built, how it was verified, any deviations from the plan, and anything left unverified or deferred.
3. **Confirm the base branch** the work split from (usually `main` or `master`; ask if unsure).
4. **Offer the integration options that apply,** with a recommendation:
   - Merge into the base branch locally
   - Push and open a pull request
   - Keep the branch as it is for later
   - Discard the work

   Leave out options that don't fit. There's no PR without a remote, and no merge without git.
5. **Carry out the choice:**
   - **Merge:** update the base branch, merge, and re-run the tests on the merged result before deleting the feature branch.
   - **PR:** push, then create the PR with a summary and a test plan. Follow the project's PR conventions.
   - **Keep:** report the branch name and workspace path.
   - **Discard:** list exactly what will be deleted (branch, commits, workspace) and get explicit confirmation first.
6. **Clean up** worktrees or scratch copies once they're no longer needed. Keep them if the user may come back (Keep, or a PR under review).

Never force-push, rewrite shared history, or delete work without an explicit request.

## Skip or shorten when

- The user has already said how to integrate. Do that.
- There's no git. Summarise, and point to any scratch copies.

## Done means

The user knows what was delivered and how it was verified, the chosen integration is complete, and nothing is left dangling.

## Next

None. The work is done.
