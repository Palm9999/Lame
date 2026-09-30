---
name: planning-work
description: Use when there is an agreed design or clear requirements for multi-step work and the order, boundaries, or checks are not yet worked out
---

# Planning Work

A plan captures intent, boundaries and how each step will be checked, so whoever executes it (you later, a subagent, or a person) can work without re-deriving decisions. It is not the implementation written twice: code in a plan is never run and goes stale the moment reality differs.

## Scale it

- **Trivial / Small:** no written plan. A short task list in the conversation is enough.
- **Substantial:** write the plan below.

## How

1. **Map the files.** List what will be created or changed and what each file is responsible for. Follow the codebase's existing patterns; split files by responsibility, not by layer.
2. **Break the work into tasks** that each leave the system working and can be verified on their own. Order them so early tasks reduce the most uncertainty. Tasks that change behavior start with their test (`testing-first`), and in a git repo each task ends in a commit. If the design spans independent subsystems, write one plan per subsystem.
3. **For each task write:**
   - **Intent:** what changes and why, in a few sentences
   - **Files:** exact paths
   - **Interfaces:** signatures, types or contracts other tasks rely on. Exact code is fine *here*.
   - **Check:** the command or observable result that proves the task is done, plus the expected outcome
   - **Risks:** anything likely to surprise the executor
4. **Mark checkpoints** where a human should look before continuing: after risky tasks, irreversible steps, or points where the design might prove wrong.
5. **Save it** following the project's convention, or `docs/plans/YYYY-MM-DD-<feature>.md`.
6. **Read it once as the executor would.** Can each task be done without asking you anything? Replace placeholders such as "TBD" or "same as task 3" with the real content.

Header for every plan:

```markdown
# <Feature> Plan
**Goal:** one sentence
**Approach:** two or three sentences
**Stack:** key technologies
**Spec:** link, if any
```

## Skip or shorten when

- The work fits in one sitting and one head. Keep a task list instead.
- The user wants to pair through it live.

## Done means

Every task has files, intent and a concrete check, and checkpoints are marked.

## Next

Offer both options with a recommendation; the user picks:

- `executing-plans` when tasks are tightly coupled or the user wants to follow along
- `delegating-to-subagents` when tasks are independent and the harness supports subagents
