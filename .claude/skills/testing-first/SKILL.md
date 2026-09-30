---
name: testing-first
description: Use when about to implement or change behavior in code that has, or should have, automated tests, including bug fixes
---

# Testing First

A test you have watched fail is proof it checks something real. A test written after the code and passing on its first run proves much less: it may test the wrong thing, or nothing. Writing the test first also makes you decide what the code *should* do before deciding how.

## Scale it

- **Behavior changes and bug fixes in tested code:** red → green → refactor, below.
- **Code without a test harness:** add a harness if it's cheap and the user agrees. Otherwise use the best available evidence (a script, a manual run with captured output) and say that's what you did.
- **Config, generated code, layout/styling, throwaway spikes:** tests are optional. Verify another way.

## How

1. **Red.** Write one small test for the next behavior, named for that behavior. Edge cases and error paths each get a test of their own. Use real code; mock only at boundaries you can't control (network, clock, third-party services). Assert on what the code does, not on what the mock received, and don't add methods to production code just for tests.
2. **Watch it fail, and for the right reason.** "Function not defined" or "expected X, got Y" is right; a typo or import error is not. If it passes straight away, it isn't testing the new behavior, so change the test.
3. **Green.** Write the simplest code that passes. Don't add options or features the test doesn't ask for.
4. **Run the relevant suite.** New and existing tests pass, with no new warnings.
5. **Refactor** with the tests green: tidy names, remove duplication. Behavior stays the same.
6. **Repeat** for the next behavior.

For bug fixes, the first test reproduces the bug. That test is the regression guard.

A test that's hard to write is feedback on the design. Heavy setup or mocks everywhere usually mean the code is too coupled, so simplify the interface or pass dependencies in.

## Wrote the code first?

Don't delete working code. Write the test now, then prove it can fail: temporarily revert or break the code under test, confirm the test fails, and restore it. If it doesn't fail, the test is too weak; strengthen it.

## Skip or shorten when

- The user says not to test or to test later. Mention the risk once, then follow their call.
- You're spiking to learn something. Throw the spike away or backfill tests before relying on it.

## Done means

Every new behavior has a test you saw fail for the expected reason and then pass, and the relevant suite is green.

## Next

- `verifying-before-claiming`
- `debugging-systematically` if a test fails in a way you don't understand
