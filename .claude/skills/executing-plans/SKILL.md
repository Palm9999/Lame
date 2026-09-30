---
name: executing-plans
description: Use when carrying out a written plan or task list yourself, in the current session
---

# Executing Plans

Work through a plan task by task, checking each one before moving on and bringing the user in where the plan or reality says they should be.

## Scale it

- **Small:** work through the task list, verifying as you go.
- **Substantial:** follow the steps below.

## How

1. **Read the whole plan critically first.** Raise gaps, contradictions or risky assumptions before starting, not halfway through.
2. **Track tasks** with whatever task tracking the harness provides. If you're on the main branch of a shared repository, check with the user before making substantial changes there, or set up a branch (`isolating-workspaces`).
3. **For each task:** implement it (with `testing-first` for behavior changes), run its check, and read the result.
4. **Stop at checkpoints** the plan marks. Summarize what was done, show the evidence, and ask whether to continue.
5. **Stop early when reality diverges.** If a check fails in a way the plan didn't anticipate, an instruction doesn't fit the code, or the approach looks wrong, pause and explain. Don't force the plan through.
6. **Keep the plan honest.** If you deviate for good reason, update the plan and say why.

## Skip or shorten when

- The plan has no checkpoints and the tasks are routine. Run straight through and report at the end.
- The user asks you to go without stopping. Still stop for failures you can't explain.

## Done means

Every task's check has run and passed (or its failure has been reported), and deviations from the plan are recorded.

## Next

- `verifying-before-claiming` before reporting completion
- `requesting-review` for substantial or risky changes
- `finishing-work` to integrate the result
