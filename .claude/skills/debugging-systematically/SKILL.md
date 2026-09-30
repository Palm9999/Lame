---
name: debugging-systematically
description: Use when encountering a bug, test failure, build error, or unexpected behavior whose cause is not already certain
---

# Debugging Systematically

Guessed fixes often hide the symptom and leave the cause, and each wrong guess adds changes you then have to untangle. Finding the root cause first is usually faster, even under pressure.

## Scale it

- **Cause is certain from the error** (a typo, a missing import, a message that names the fix): fix it and verify.
- **Anything else:** follow the phases below.

## How

**1. Investigate**
- Read the full error and stack trace: file, line, code.
- Reproduce reliably. If you can't, gather more data before theorising.
- Check what changed recently: diffs, dependencies, config, environment.
- In multi-component systems, log what enters and leaves each boundary, then run once to see *where* it breaks.
- Trace the bad value back to where it came from and fix it there, not where it surfaced.

**2. Compare**
- Find similar code that works and list every difference, however small.
- If you're following a reference implementation, read all of it.

**3. Hypothesise and test**
- State one hypothesis: "X causes this because Y."
- Test it with the smallest possible change, one variable at a time.
- If it's wrong, undo the change and form a new hypothesis; don't stack fixes.
- If you don't understand something, say so and research or ask.

**4. Fix**
- Write a failing test that reproduces the bug (`testing-first`).
- Make one change that addresses the root cause, without bundling in cleanup.
- Verify the test passes, the original symptom is gone, and nothing else broke.
- If the bad value could arrive by other routes, add validation where it enters each layer, not only where it failed.
- For timing-dependent failures, wait on the condition you need rather than a fixed delay.

**After three failed fixes, stop.** Repeated failures that each reveal a new problem somewhere else usually mean the design is wrong, not that the next fix will work. Summarise what you've learned and discuss the approach with the user.

## Skip or shorten when

- The investigation shows the cause is truly external (timing, environment, a third party). Document what you checked, add sensible handling (retry, timeout, clear error), and add logging for next time.

## Done means

You can explain the root cause, a test reproduces it and now passes, and the original symptom is gone.

## Next

- `verifying-before-claiming`
- `investigating-in-parallel` if you uncover several independent problems
