---
name: using-tackle
description: Use when deciding how much process a coding task needs, or when unsure which Tackle skill fits the work in front of you
---

# Using Tackle

Tackle skills are tools, not a pipeline. Pick the ones that fit the task, skip the rest, and say briefly which you're using when it helps the user follow along.

## Scale it

| Size | Looks like | Process |
|---|---|---|
| Trivial | Typo, rename, config value, one obvious line | Do it, then verify |
| Small | One coherent change, approach is clear | State intent in a sentence, test first where a harness exists, verify |
| Substantial | Several components, unclear approach, or costly to undo | Shape the design, plan, execute with checkpoints |

When in doubt between two sizes, pick the smaller and step up if surprises appear.

## How

**Precedence:** the user's explicit instructions (including project instruction files such as CLAUDE.md, AGENTS.md or GEMINI.md) come first, then the harness and its system prompt, then Tackle. A skill never overrides the first two.

**Platforms:** skills describe tools by role ("dispatch a subagent", "track tasks"). `references/platforms.md` maps each role to Claude Code, Codex and Gemini CLI, with fallbacks for missing ones.

**Skill map:**
- Shaping an idea: `shaping-designs` → `planning-work`
- Doing the work: `executing-plans`, `delegating-to-subagents`, `investigating-in-parallel`, `isolating-workspaces`
- Quality: `testing-first`, `debugging-systematically`, `verifying-before-claiming`
- Review: `requesting-review`, `receiving-review`
- Wrapping up: `finishing-work`
- Extending Tackle: `writing-skills`

When several skills fit, start with the one that decides the approach (`shaping-designs`, `debugging-systematically`), then the ones that carry it out.

## Skip or shorten when

- The user says how they want to work. That wins over any sizing.
- The task is trivial. Don't reach for a skill at all.

## Done means

You've picked a size and the matching skills, or consciously none.

## Next

Whichever skill fits. Each one ends with suggestions; you and the user decide.
