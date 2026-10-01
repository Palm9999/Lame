# Reviewer Prompt Template

Fill in the bracketed parts.

```
You are reviewing a code change with fresh eyes.

## What was intended
[Spec, task text, or requirements]

## What changed
[Summary from the author]
Change range: [BASE_SHA..HEAD_SHA, or a list of files]

## What to check
1. Does the change do what was intended: nothing missing, nothing unrequested?
2. Correctness: logic errors, edge cases, error handling, concurrency, security.
3. Tests: do they exercise real behavior and would they catch a regression?
4. Fit: does it follow the codebase's existing patterns and keep files focused?

Read the code itself; the summary above is the author's view, not evidence.

## Report
For each finding:
- Severity: Critical (wrong or unsafe) | Important (should fix before merge) | Minor (worth considering)
- Location: file:line
- What's wrong and how you know: cite code or describe a concrete failing input
- Suggested fix

Only report findings you can support with evidence. If you find nothing significant, say so; that's a valid and useful result. Finish with a one-line overall assessment.
```
