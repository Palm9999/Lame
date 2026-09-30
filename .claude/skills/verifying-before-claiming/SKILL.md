---
name: verifying-before-claiming
description: Use when about to say work is done, fixed, passing, or ready, or before committing, opening a PR, or moving to the next task
---

# Verifying Before Claiming

A claim of success is only as good as the evidence behind it. Unverified claims cost the user more than an honest "not checked yet": they build on the claim, and the failure turns up later, when it's harder to trace.

## Scale it

- **Trivial:** run the one check that proves the change, e.g. build or lint the touched file.
- **Small / Substantial:** match every claim to fresh evidence, below.

## How

For each claim you're about to make:

1. **Identify the proof.** Which command or observation would show it's true?
2. **Run it now,** in full. An earlier run from before your last change doesn't count.
3. **Read the output:** exit code, failure count, warnings.
4. **Report what it shows,** with the evidence. If it contradicts the claim, report the actual state instead.

If you're about to write "should work", "looks right" or "probably fixed", that's a sign the check hasn't run yet.

| Claim | Evidence | Not enough |
|---|---|---|
| Tests pass | Test run: 0 failures | An earlier run, "should pass" |
| Build works | Build exits 0 | Linter passing |
| Bug fixed | Original symptom re-tested | Code changed |
| Regression test works | Seen failing without the fix, passing with it | Passes once |
| Subagent finished | You read its diff and ran its check | Its report |
| Requirements met | Each requirement checked against the result | Tests green |

## When you can't verify

Say so plainly: what you couldn't run, why, and what the user should check. "Implemented but untested because there's no test database here" is useful. Implying success isn't.

## Skip or shorten when

- The user explicitly waives verification. State once what hasn't been checked.

## Done means

Every success claim in your message is backed by evidence produced after the last change, and every unverified part is labelled.

## Next

- `finishing-work`
- `requesting-review` for substantial or risky changes
