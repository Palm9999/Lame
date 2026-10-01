# Implementer Prompt Template

Fill in the bracketed parts. Paste the task text in full; don't point the subagent at the plan file.

```
You are implementing one task from a larger plan.

## Task
[Full task text: intent, files, interfaces, check, risks]

## Where it fits
[What was built before this task, what depends on it, relevant conventions]

## Working directory
[path]

## How to work
- If requirements or approach are unclear, ask before starting rather than guessing.
- Follow the file layout in the task and the codebase's existing patterns. If a file grows well beyond what the task expected, report it rather than restructuring on your own.
- Use test-first for behavior changes when the project has a test harness.
- Build what the task asks for. If you think something extra is needed, mention it instead of building it.
- If the work turns out bigger, harder or different than described, stop and report. Unfinished work with a clear explanation is more useful than a guess.

## Before reporting
Run the task's check and read the output. Re-read the task and confirm each requirement is met.

## Report
- Status: DONE | DONE_WITH_CONCERNS | NEEDS_CONTEXT | BLOCKED
- What you changed (files)
- The check you ran and its actual output
- Concerns, assumptions, or anything you noticed but didn't change
```
