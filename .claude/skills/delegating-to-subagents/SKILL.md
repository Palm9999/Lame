---
name: delegating-to-subagents
description: Use when executing a plan whose tasks are independent enough to hand to subagents, and the harness supports dispatching them
---

# Delegating to Subagents

Hand each task to a fresh subagent with exactly the context it needs, while you act as coordinator. This keeps your own context clear for integration and judgment. Delegation costs time and tokens, so match the amount of review to the risk of each task.

## Scale it

Choose per task, not per plan:

| Task risk | Implementer | Review |
|---|---|---|
| Routine (clear spec, 1–2 files) | Fast, cheaper model | You read the diff and run its check |
| Integration (several files, judgment needed) | Standard model | Diff + check, plus a reviewer if anything looks off |
| Risky (security, data, public API, hard to undo) | Most capable model | Diff + check + independent reviewer (`requesting-review`) |

## How

1. **Extract every task from the plan up front** so each subagent gets the full task text rather than reading the plan file.
2. **Dispatch one implementer at a time** for tasks that touch shared files. Use `implementer-prompt.md`. Include where the task fits, which interfaces it relies on, and its check.
3. **Handle the status it reports:**
   - **DONE:** verify it (below).
   - **DONE_WITH_CONCERNS:** read the concerns. Resolve those about correctness before verifying; note the rest.
   - **NEEDS_CONTEXT:** supply what's missing and re-dispatch.
   - **BLOCKED:** change something before retrying: more context, a stronger model, a smaller task, or escalate to the user if the plan is wrong. Never retry unchanged.
4. **Verify independently.** Read the actual diff and run the task's check yourself. A subagent's report is a claim, not evidence.
5. **Fix proportionately.** Small, clear fixes you can make directly. Larger ones go back to a subagent with the specific issue.
6. **Pause at checkpoints** the plan marks, and whenever several tasks in a row surprise you.
7. **Finish with a whole-change check:** full test suite, plus one review of the complete diff for substantial work.

## Skip or shorten when

- The harness can't dispatch subagents (see `using-tackle/references/platforms.md`). Use `executing-plans`, keeping the risk-matched checks above.
- Tasks are tightly coupled or each depends on the last. Use `executing-plans`.
- There are only one or two small tasks. Dispatch overhead outweighs the benefit.
- The user hasn't agreed to subagent use and the harness asks you to confirm first.

## Done means

Every task's diff has been read and its check run by you, review has matched each task's risk, and the full suite passes.

## Next

- `verifying-before-claiming`
- `finishing-work`
