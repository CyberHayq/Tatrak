---
type: Runbook
title: EGM review routing and Goldfish isolation
description: Which model and effort runs each EGM Goldfish reviewer, how a Codex reviewer is started in isolation, and why (the probes behind it stay in the development repository).
tags: [ops, egm, review, codex, orca, runbook]
status: draft
generated: { by: claude-code/claude-opus-5-5, at: 2026-11-09 }
sources:
  - id: rules
    resource: ../../AGENTS.md
    title: Section "EGM reviews" (points to this page, which holds the rules)
  - id: egm-claude
    resource: ~/.claude/plugins/cache/egm/egm/0.1.0/docs/claude.md
    title: EGM Claude Code integration (direct dispatch, reviewer isolation)
  - id: egm-codex
    resource: ~/.claude/plugins/cache/egm/egm/0.1.0/docs/codex.md
    title: EGM Codex integration (fresh-context reviewers, memory caveat)
---

`AGENTS.md` (section "EGM reviews") sends every EGM review here. The rules come first; the rest
of the page records why they look that way, so a later version of Claude Code, Codex, Orca or
EGM can be checked against the same probes (the probes stay in the development repository).

# Rules

- The main session is the Elephant and starts the reviewers itself, without `egm-synthesizer`. It
  does the synthesizer's work: merges duplicate findings, keeps the reviewer status table, marks a
  failed reviewer `not run`. It does not edit the target document while a round runs, so every
  reviewer of the round sees the same text.
- A Claude reviewer is always a native `egm:egm-*` subagent (read-only tools, no `CLAUDE.md`)
  started with the model and effort below, never an Orca worker.
- A Codex reviewer is a second, independent instance of the same lens, started with the command
  below. Comprehension never runs on Codex: the naive reader must not see project instructions.

| Stage: reviewer | Claude: model, effort | Codex: model, effort |
|---|---|---|
| 5, 8: zoom-out | opus, high | - |
| 6: comprehension | opus, medium | - |
| 7: design | opus, high | gpt-6.2-sol, high |
| 7, 9: structural, style | sonnet, medium | - |
| 9: implementation-readiness, codebase-details | opus, high | gpt-6.2-sol, high |

- A document about Tatrak's own storage or encryption (database, attachments, backup/import)
  gets Codex `gpt-6.2-astra` at high instead of `gpt-6.2-sol` (`-m` and
  `model_reasoning_effort` in the command below).
- These are starting efforts. Raise a lens to xhigh only after a later round or the owner finds
  something it missed, and say so in `knowledge/log.md`.
- Each finding in the report names its reviewer, instance (Claude or Codex), model, effort and
  round. When Claude and Codex disagree, keep both views; do not decide by vote.
- Codex Goldfish command. It turns off Codex memory, project `AGENTS.md`, account apps, plugins
  and web search, and keeps the sandbox read-only; Codex's own agent tools may remain (the
  flags do not remove them), so the brief forbids them. The brief goes in a file and must
  carry what the reviewer no longer gets from `AGENTS.md`: absolute paths of the persona, the
  standard and the target with its line range, the Goldfish context, "read-only, start no
  agents", and the report format.

  ```sh
  orca terminal create --worktree current --title "goldfish <lens> (codex)" --command \
    "codex exec --ephemeral -s read-only -m gpt-6.2-sol -c model_reasoning_effort=high \
     -c features.memories=false -c features.apps=false -c features.plugins=false \
     -c features.multi_agent=false -c features.goals=false -c features.image_generation=false \
     -c web_search=disabled -c project_doc_max_bytes=0 \
     -C <repo> -o <report.md> - < <brief.md>; exit" --json
  orca terminal wait --terminal <handle> --for exit --timeout-ms <ms> --json
  ```

  For a Goldfish run this replaces the reporting rule of `AGENTS.md` ("Handing work to
  another agent"): the owner sees the tab, the Elephant
  reads `<report.md>`. A non-zero exit or a missing report file means `not run`.

# Why this routing

* Claude Code subagents take a per-call `model` and `effort`. EGM's Claude adapters declare
  `model: inherit` and no effort, so without the routing table every reviewer runs at the parent
  session's effort, style included.
* Orca workers are full agent sessions: they load `CLAUDE.md`/`AGENTS.md` and have every tool.
  Native `egm:egm-*` subagents have `Read, Grep, Glob` and `omitClaudeMd: true`. Claude reviewers
  therefore stay native; Orca only hosts the Codex reviewers.
* `egm-synthesizer` has no Bash, so it cannot start Codex. EGM allows direct dispatch from the
  main session (`docs/claude.md`, `skills/egm-review-loop/SKILL.md`, "Direct Dispatch").
* A second model has different blind spots; the EGM synthesizer persona already suggests two
  instances A and B per reviewer. Codex is the B instance for the code-grounded lenses.
* Structural and style run on Sonnet at medium, not low: they read files and apply a standard,
  and Anthropic's guidance starts Sonnet 5.5 at medium for multi-step tool use.
* Documents about Tatrak's storage or encryption get Codex `gpt-6.2-astra` at high, the same
  model that reviews that code (see "Models for coding" in `AGENTS.md`).


