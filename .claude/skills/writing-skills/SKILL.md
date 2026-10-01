---
name: writing-skills
description: Use when creating a new Tackle skill, editing an existing one, or checking that a skill changes agent behavior the way it should
---

# Writing Skills

A skill is reusable judgment: how to approach a kind of task, and when not to. Good skills are short, explain their reasoning, and are tested against real scenarios, including ones where the skill should stay quiet.

## Scale it

- **Wording tweak:** edit, then re-read the skill as an agent would.
- **New skill or behavioral change:** follow the steps below, including evals.

## How

1. **Start from observed behavior.** Run the scenario without the skill and note what the agent actually does wrong. If nothing goes wrong, you may not need the skill.
2. **Write the frontmatter:**
   - `name`: lowercase-hyphenated, verb-led (`testing-first`, `finishing-work`)
   - `description`: *when* to use it: triggering situations and symptoms, starting with "Use when…", under 1024 characters. Use the words an agent would have in mind at that moment, such as error text, tool names and symptoms. Don't summarize the steps; agents may follow the summary instead of reading the skill.
3. **Follow the Tackle template** so every skill reads alike:
   - Purpose paragraph: what it achieves and why it matters
   - `## Scale it`: what to do at each size (trivial / small / substantial, or risk levels)
   - `## How`: the steps
   - `## Skip or shorten when`: explicit exits
   - `## Done means`: the observable finish line
   - `## Next`: suggested follow-on skills, never mandated
4. **Write for a capable colleague.** Give reasons instead of volume: no ALL CAPS, threats, or "no exceptions". Where a rule really is firm, say why it's firm. Never claim precedence over the user or the harness.
5. **Keep it lean:** under 500 words, with detail moved into supporting files in the skill's folder. Put each fact in one place; don't restate the steps as a diagram and a checklist. One complete, realistic example teaches more than several partial ones.
6. **Stay portable:** no personal names or in-jokes, no branded paths in user repos, git optional, and tools described by role ("run the tests", "dispatch a subagent") rather than one harness's names.
7. **Add eval scenarios** to `evals/scenarios.md`: at least one where the skill should apply, and one where it should not (usually a trivial task).
8. **Measure against baseline.** Compare runs with and without the skill on outcome, turns and tokens. When a run goes wrong, find the wording that allowed it and fix it with a reason. Keep a skill only if it helps.

## Skip or shorten when

- The guidance is project-specific. It belongs in that project's instruction file (CLAUDE.md, AGENTS.md, GEMINI.md), not in Tackle.

## Done means

The skill follows the template, stays within budget, and has should-apply and should-not-apply scenarios with baseline results noted.

## Next

- `requesting-review` for the skill text itself
