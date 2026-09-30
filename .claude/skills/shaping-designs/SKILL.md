---
name: shaping-designs
description: Use when the user wants to build or change something and the goal, scope, or approach is not yet clear enough to start
---

# Shaping Designs

Turn an idea into a design the user agrees with before effort goes into building it. Most wasted work comes from building the wrong thing confidently, so the aim is shared understanding, not paperwork.

## Scale it

- **Trivial:** skip this skill.
- **Small:** state the intended change in one or two sentences. Ask only if something is genuinely ambiguous, then proceed.
- **Substantial:** follow the steps below.

## How

1. **Look before asking.** Read the relevant code, docs and recent history so your questions are about intent, not facts you could look up.
2. **Check scope.** If the request spans several independent subsystems, propose splitting it and shape the first piece.
3. **Ask what you need.** Group related questions in one message (three or four at most) and prefer multiple choice. Cover purpose, constraints and what success looks like.
4. **Offer approaches when there is a real choice.** Give two or three options with trade-offs and lead with your recommendation. If one approach is obviously right, say so and move on.
5. **Present the design** at a length that matches its complexity: components and their boundaries, data flow, error handling, and how it will be tested. Design units that can be understood and tested on their own. Cut anything the stated goal doesn't need. In an existing codebase, follow its patterns, and limit refactoring to what this work touches.
6. **Get agreement** on the design as a whole. Revise where the user pushes back.
7. **Write it down if it will outlive the conversation.** Follow the project's existing docs convention; otherwise use `docs/specs/YYYY-MM-DD-<topic>.md`. Commit only if the project is in git and the user works that way.

For high-risk designs, an independent reviewer can read the written spec for gaps and contradictions (see `requesting-review`). Treat its findings as input, not a gate.

## Skip or shorten when

- The user already has a spec or clear instructions. Confirm your understanding and go.
- The user asks you to just build it. Note any assumptions you made instead of asking.
- The work is exploratory. A throwaway spike can answer design questions faster than discussion.

## Done means

The user has agreed to the design, or explicitly waived it, and any open questions are written down rather than silently assumed.

## Next

- `planning-work` for multi-step work
- Straight to implementation (with `testing-first`) for small, well-understood changes
