# Eval Scenarios

Prompts for checking that each skill fires when it should, stays quiet when it shouldn't, and improves on a run without Tackle.

For each scenario, record with and without Tackle: whether the expected behavior happened, number of turns, tokens used, and whether the result was correct. Keep a skill only if it helps.

## Running them

Scenarios with a directory under `cases/<skill>/<name>/` run automatically; the rest are still manual. Node 18+ and the `claude` CLI are required.

```bash
node evals/run.mjs                               # every case, 3 runs per arm
node evals/run.mjs --case debugging --runs 1     # quick pass over matching cases
```

Each run copies the case's `fixture/` into a fresh temp directory and runs `claude -p` there, once with Tackle (`--plugin-dir`) and once without. Your user settings are excluded from both arms, so plugins you have installed, such as superpowers, don't skew the baseline. The runner then:

- records whether the case's skill fired, and the turns, tokens and cost of the run;
- records whether the run delegated to a subagent. Turns count the main thread only, so a run that hands the work to a subagent looks artificially cheap; the **Delegated** column marks those so the turn comparison isn't read as like-for-like. Subagent activity is included in the judged transcript, marked `[subagent]`, because the work itself may happen there;
- runs the case's `check.mjs`, if it has one, against the work directory;
- has a judge model grade the transcript against `graders/criteria.md`.

Results, raw transcripts and a `summary.md` go to `evals/results/<timestamp>/`. Runs where the harness itself misbehaved (Tackle failed to load, another plugin loaded, the shell was broken, a timeout) are listed but left out of the averages.

Every run uses the same fixed tool set with permission prompts off, so denied commands can't skew turns or outcomes. The agent can therefore run any command without asking, so only run cases you've read.

To add a case, copy an existing case directory and change `case.json` (`skill`, `expect`: `apply` or `skip`), `prompt.md`, `fixture/`, `graders/criteria.md` and, where the outcome can be checked mechanically, `check.mjs`. Make the check fail on the tempting wrong fix, not only on the original bug.

## Scenarios

| Skill | Should apply | Should NOT apply (expected behavior) |
|---|---|---|
| using-tackle | "Add OAuth login and an admin dashboard to this app" → sizes as substantial | "Fix the typo in README line 3" → just fixes it |
| shaping-designs | "I want some kind of caching for the API, not sure what" → asks grouped questions, offers approaches | "Change the timeout constant from 30 to 60" → no design discussion |
| planning-work | Agreed spec for a 3-component feature → plan with checks and checkpoints, no full code | "Rename `getUser` to `fetchUser`" → no written plan |
| executing-plans | Plan with a checkpoint after task 2 → stops at task 2 with evidence | User says "just run it all" with routine tasks → no stops except for failures |
| delegating-to-subagents | Plan with 5 independent tasks, one touching auth → auth task gets reviewer, others get diff + check | Plan with 2 tightly coupled tasks → recommends executing-plans |
| investigating-in-parallel | 3 test files failing in unrelated subsystems → parallel dispatch | 3 failures all in one module after one commit → investigates together |
| isolating-workspaces | Long-running refactor in a git repo → branch/worktree, baseline recorded | One-line fix, or a non-git directory → works in place |
| testing-first | "Fix: empty email is accepted" in a tested codebase → failing test first | "Update the brand colour in the CSS" → verifies visually, no test ritual |
| debugging-systematically | Intermittent test failure → reproduces, gathers evidence, one hypothesis at a time | `ModuleNotFoundError: requests` → installs and verifies |
| verifying-before-claiming | After implementing a fix → runs the suite and quotes the result | No test DB available → says what couldn't be verified |
| requesting-review | Payment-handling change complete → deterministic checks, then reviewer, then verifies findings | Formatting-only change with passing checks → no reviewer |
| receiving-review | Reviewer asks to "implement metrics properly" on an unused endpoint → searches for callers, proposes removal | Single clear, correct nit → fixes it without ceremony |
| finishing-work | Feature on a branch, tests green → offers applicable options with a recommendation | Non-git directory → summary only, no merge/PR options |
| writing-skills | "Add a skill for database migrations" → template, budget, evals | "Add this project's lint command to the agent instructions" → edits CLAUDE.md / AGENTS.md / GEMINI.md, no skill |
