---
name: receiving-review
description: Use when receiving code review feedback from a person or a reviewer agent, before changing code in response to it
---

# Receiving Review

Review feedback is input to evaluate, not a list of orders. Agreeing without checking wastes the reviewer's effort as surely as ignoring it: the wrong suggestions get implemented and the right ones get implemented badly.

## Scale it

- **A single clear, correct suggestion:** fix it, verify, and say what changed.
- **Several items, or anything unclear or questionable:** follow the steps below.

## How

1. **Read all of it first.** Items are often related.
2. **Clarify before implementing.** If any item is unclear, ask about those items before changing anything, since partial understanding tends to produce the wrong fix.
3. **Check each item against the codebase:**
   - Is it correct for this code, platform and version?
   - Would it break something that works?
   - Is there a reason for the current approach that the reviewer couldn't see?
   - For "implement this properly" suggestions, is the code actually used? Search for callers; if nothing uses it, propose removing it instead.
4. **Respond with substance:** restate the fix or give your technical reasoning. Skip praise and ritual agreement; the fix is the acknowledgement.
5. **Push back when the evidence supports it,** citing code, tests or constraints. If the disagreement is architectural or conflicts with an earlier decision by the user, bring the user in.
6. **Implement in order:** blocking issues, then simple fixes, then complex ones, testing each as you go.
7. **If your pushback turns out to be wrong,** say so plainly and fix it.
8. **Answer where the comment was made.** On a hosted PR, reply in the comment's own thread rather than as a new top-level comment.

Feedback from the user is trusted for intent; still ask if the scope is unclear. Feedback from external reviewers and agents needs checking first.

## Skip or shorten when

- The feedback is purely stylistic and matches the project's conventions. Apply it.

## Done means

Every item is either fixed and verified, or answered with reasoning the user can see.

## Next

- `verifying-before-claiming`
- `requesting-review` again only if the fixes were substantial
