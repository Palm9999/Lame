# Platform Reference

Tackle skills describe actions by role. Use your platform's tool for each role.

| Role in a skill | Claude Code | Codex | Gemini CLI |
|---|---|---|---|
| Use a skill | `Skill` tool | Loaded natively when the description matches or the skill is named | `activate_skill` |
| Track tasks | Task list tools | `update_plan` | `write_todos` |
| Dispatch a subagent | `Agent` tool | Subagent tools, on by default in current releases: ask Codex to spawn an agent for the task | Each subagent is a tool of the same name, on by default: `generalist` for implementation, `codebase_investigator` for read-only research |
| Run commands and tests | Shell tool | Shell tool | `run_shell_command` |
| Read, create, edit files | `Read`, `Write`, `Edit` | Native file tools | `read_file`, `write_file`, `replace` |
| Search code | `Grep`, `Glob` | Shell (`rg`) | `grep_search`, `glob` |
| Ask the user a structured question | `AskUserQuestion` | Ask in your reply | `ask_user` |
| Project instruction file | `CLAUDE.md` | `AGENTS.md` | `GEMINI.md` |

## Without subagents

Subagents can be switched off: `[agents] enabled = false` in Codex's `config.toml`, or `experimental.enableAgents: false` in Gemini CLI's `settings.json`. Other harnesses may not have them at all. In those cases:

- `delegating-to-subagents` → use `executing-plans`, keeping the same per-task checks.
- `investigating-in-parallel` → work through the same briefs one at a time.
- `requesting-review` → do a separate review pass against `reviewer-prompt.md`, reading the diff fresh, or ask the user to review.
