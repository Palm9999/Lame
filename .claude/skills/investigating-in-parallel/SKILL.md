---
name: investigating-in-parallel
description: Use when facing several independent problems, such as unrelated test failures or separate subsystems, that could be investigated at the same time without sharing state
---

# Investigating in Parallel

When problems are truly independent, one focused agent per problem finishes faster than working through them in sequence, and each agent stays focused on its own problem.

## Scale it

- **Two small problems:** usually faster to handle yourself in sequence.
- **Three or more independent problems, or slow investigations:** dispatch in parallel.

## How

1. **Group by root domain, not by symptom.** Failures in the same subsystem, or ones that could share a cause, belong to one agent.
2. **Check independence.** Would the agents edit the same files or share a resource (database, port, fixture)? If so, run them in sequence.
3. **Give each agent a self-contained brief:**
   - **Scope:** one file, subsystem or failure set
   - **Evidence:** the exact error messages and test names
   - **Constraints:** what it must not change
   - **Output:** root cause, what it changed, and how it verified the change
4. **Dispatch together**, then integrate: read each summary and diff, look for overlapping edits, and run the full suite on the combined result.

## Skip or shorten when

- The failures might be related. Investigate together first, because one fix may clear several.
- You don't yet know what's broken. Exploration needs one coherent view.
- The harness has no subagents. Work through them in sequence using the same briefs as a checklist.

## Done means

Each agent's change has been read, conflicts are resolved, and the full suite passes on the combined result.

## Next

- `debugging-systematically` for any problem an agent couldn't explain
- `verifying-before-claiming`
