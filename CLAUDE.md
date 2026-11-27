@AGENTS.md

## Claude Code specifics

- All project rules are in `AGENTS.md` above; keep them there, not here, so other agents get
  them too. This file only adds what is specific to Claude Code.
- When the owner asks for a commit, end the message with the attribution line
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Long or independent work (reviews, research across other source trees) can go to a
  background subagent; give it the relevant rules from `AGENTS.md`, since it does not see this
  conversation, including "Handing work to another agent": the report goes to the owner and
  back to this session.
- The built-in `Explore` agent runs on the main session's model (Opus), not Haiku: for the reading
  row of the model table pass `model: "haiku"` (or `"sonnet"`). The main session still reads the
  diffs it re-checks and the review findings it decides on itself.
