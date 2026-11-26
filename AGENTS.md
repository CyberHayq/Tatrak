# Tatrak: instructions for coding agents

Tatrak (`am.cyberhayq.chat`) is a fork of the Android XMPP client Conversations with a
Telegram-style Compose UI. Goal: Telegram-level smoothness and look; Conversations' protocol and
encryption stay as they are. Project knowledge lives in `knowledge/` (start at
`knowledge/index.md`); this file holds the working rules the owner has given.

## Talking to the owner

- Reply in Russian (the owner writes Russian). Code, code comments, commit messages and the
  `knowledge/` bundle are in English.
- Report results as they are: what was built, what was tested and how, what was not checked.
  Everything unchecked or risky also goes into `knowledge/project/risks.md`.
- A question is not an instruction: "what if we commit X?" gets an answer (can it be done, pros,
  cons, a recommendation) and a wait; nothing is moved, edited or staged until the owner says so.
- Decisions go to the owner one at a time: one question, a concrete example, every difference
  between the options and a recommendation. No bundled questions.
- Before designing a migration or an upgrade path, ask whether real installs exist. Tatrak has
  no users yet; the owner's test installs are reinstalled clean when needed.

## Commits

- The owner signs commits with a hardware key: prepare changes in the working tree (or staged),
  **commit only when asked**.
- Code copied from another project (see reference clones below) names its source (project,
  file, commit) in the commit message.
- Nothing committed contains an absolute path that shows a user name (`/home/<user>/...`): use
  repository-relative paths, or `~/` for files outside the repo. Every `.md` file is English:
  translate Codex reports that come back in Russian and quotes of the owner's words. Check before
  staging: `git grep --cached -n "/home/"` (upstream SVGs in `art/` excepted) and
  `git grep --cached -lP '[\x{0400}-\x{04FF}]' -- '*.md'`.
- Tests use generated media, never the owner's personal files (`~/Pictures` and the like).
- Every commit goes to the public repository with its message (`knowledge/ops/public-export.md`).
  Private `knowledge/` pages (outside `PUBLIC_KNOWLEDGE` in `tools/public-export/export.py`) may
  share a commit with code only when the message says nothing they alone hold. Anything private
  (another project's defect, a finding, owner-machine details) goes in a commit of its own that
  touches only private pages.
- Before every commit and every push to the public repository, bring `README.md` and the public
  Markdown that goes with the change up to date: `CHANGELOG.md` and the public `knowledge/` pages
  (`PUBLIC_KNOWLEDGE` in `tools/public-export/export.py`) that describe what changed (features,
  screens, build steps, supported XEPs, roadmap). A commit whose change makes any of them wrong
  updates them in the same commit; if none needs a change, say so to the owner before the commit.

## What may change

- **Security and encryption code stays untouched** (`knowledge/decisions/security-untouched.md`):
  OMEMO / OpenPGP, `crypto/`, key storage and trust, choice of message encryption, TLS, SASL,
  DNSSEC, the `xmpp/` package and stanza parsers. The new UI only calls the existing APIs and
  opens the existing security screens. Changes elsewhere outside `ui/`, `res/` and
  `entities/Conversation.Attributes` need the owner's agreement first.
- **Reuse the legacy screens' actions and dialogs 1:1**: call the same methods in the same order
  (permission checks, call start, file save, …) instead of rewriting them; share a helper when two
  screens need the same flow.
- **No new dependencies.** Smallest complete change; no speculative extras.
- **Upstream merge policy** (`knowledge/decisions/upstream-merge-policy.md`):
  - new Kotlin code under `src/main/kotlin/am/cyberhayq/chat/`; only bridge classes that need
    package-private access go to `src/main/kotlin/eu/siacs/conversations/ui/`;
  - new resources in separate `tatrak_*` files with `tatrak_`-prefixed names, English in `values/`
    and Russian in `values-ru/`; upstream resource files are not edited for new strings;
  - string literals with `tatrak` are often persisted data (preference keys, DB columns, the
    `tatrak-db-key-v1` AAD, `tatrak-databases`, backup format value, `android:key`): renaming
    one breaks existing installs unless a migration is written;
  - upstream Java/XML edited only where unavoidable, with a short comment;
  - `android.namespace` stays `eu.siacs.conversations`.

## Features that exist elsewhere

- Before building a feature that monocles chat (or another reference app) already has, look at
  its implementation: use it if it fits the rules above (no new dependencies, fast enough, safe),
  otherwise build our own and record why in `knowledge/project/reference-sources.md`.
- Before building any feature, also look at how Telegram and the other messengers (Conversations,
  monocles, Signal and the like) do it, including the fixes in the history of that code (commit
  log of the files, their issue tracker), so Tatrak does not repeat bugs they already fixed (owner,
  2026-11-24). The plan or report says what was read and what was taken from it. The
  `../telegram-ref` clone is shallow: read Telegram's history on GitHub (`gh api`).
- Reference clones next to the repo are read-only: `../monocles-ref` (AGPL-3.0),
  `../telegram-ref`, `../grapheneos-messaging-ref` (re-clone instructions in
  `knowledge/project/reference-sources.md`). Check the licence before copying: Tatrak is GPL-3.0, so AGPL code is not copied (see `knowledge/project/reference-sources.md`).
- Every bug or weakness found in Tatrak (review, audit, testing) is also checked in current
  Conversations upstream and monocles (a subagent may do the check). If one of them has it too,
  record it outside this repository, one file per finding in `../tatrak-private-findings/findings/`
  (where, how to reproduce, our fix; add it to that folder's `README.md`), so the owner can file
  issues with their developers later. That folder is a clone of a separate private repository:
  commit and push the finding there.
- A defect of another project that is not public yet never goes into the repository: not in
  code comments, commit messages, `knowledge/` or `docs/`; it lives only in
  `../tatrak-private-findings/` (its own private repository) until it is disclosed. Older details
  in `docs/` sit between private markers, which the public export cuts
  (`knowledge/ops/public-export.md`).

## Handing work to another agent

- A task brief given to another agent (another session, Codex, a subagent) always says where
  to report: the finished agent sends its report **both to the owner and to the agent that
  handed the task over** (for an Orca terminal:
  `orca terminal send --terminal <handover agent's handle> --text '<report>' --enter`).
- The report names the changed files, the test and build results, and what was not checked.
  The handing-over agent then re-checks the changes before telling the owner they are done.
- An agent that receives such a task follows this too, and passes the rule on in any brief it
  writes itself.
- Start the other agent (a Codex review, another Claude session) through Orca when the `orca`
  CLI is available (`orca agent launch`, or `orca worktree create --agent` for a separate
  checkout; see the `orca-cli` skill), so the owner sees it in a terminal tab and the report can
  come back with `orca terminal send`. Without Orca, run it directly (for example
  `codex exec -s read-only "<brief>" > <file>` in the background) and read its output file.

## Parallel work (owner, 2026-11-20)

- The main session is the orchestrator: it plans, asks the owner the decisions, writes the briefs,
  starts the agents, re-checks their changes, runs the reviews and resolves them, keeps
  `knowledge/` and reports to the owner. It writes code itself only for small changes: a few
  lines, a fix a review already spells out, docs and strings.
- A large or independent task (a feature, a new screen family, anything with its own tests)
  goes to an agent in its own worktree and branch (`orca worktree create --agent`), so its diff
  stays apart and it can be committed on its own. Small tasks may run in the main working tree.
- At most two or three agents at once: one Gradle build at a time on this machine, so every brief
  says to wait for a running build before starting one, with a time limit:
  `timeout 1800 sh -c 'while pgrep -f "[G]radleWrapperMain" >/dev/null; do sleep 20; done'`.
- The briefs name the shared files a task may touch (`tatrak_strings.xml`, `ChatFragment.kt`,
  `knowledge/log.md`, `risks.md`, `backlog.md`): agents only append to the knowledge pages, and
  the main session merges the branches and resolves conflicts there.
- Finished work is committed (when the owner asks) before new tasks start in the same tree, so
  tasks do not mix in one working tree.
- An agent's report names the main session's current Orca terminal handle (`$ORCA_TERMINAL_HANDLE`
  of the main session, read at the time the brief is written; it changes when a session restarts).

## Models for coding

| Work | Who | Effort |
|---|---|---|
| Main session: plans, designs, resolving reviews, re-checking others' changes | Claude Opus 5.5 | xhigh |
| A feature or fix with a written plan | Claude Opus 5.5 subagent | high |
| Mechanical work: strings and translations, screenshot tests, throwaway probes, emulator checks | Claude Sonnet 5.5 | medium |
| Measurements on the `benchmark` build, a batch of fixes in its own worktree | Codex `gpt-6.2-sol` | high |
| Very complex work: Tatrak's own storage and encryption code, migrations, races | Claude Opus 5.5 subagent writes, Codex `gpt-6.2-astra` reviews | high / high |
| Small fixes to storage or encryption code from a review whose findings already give the fix | Claude Opus 5.5 (main session) | xhigh |
| A bug whose root cause the main session did not find | Codex `gpt-6.2-astra` | high |

- The table covers every kind of work in a task, not only writing code. When the work changes
  kind (writing, emulator checks, docs), look at the table again; before running mechanical work
  such as emulator checks on the main session, ask the owner to hand it to Sonnet instead (the
  main session once ran three rounds of emulator checks itself on Opus and Fable).
- Pass the model and effort explicitly: a Claude subagent started without them inherits the main
  session's model and effort.
- Sonnet gets no Compose state or other logic changes; it missed a state bug in `DrawerHost`.
- A Codex brief for code says to call the legacy methods as they are, not rewrite them (a Codex
  fix batch once replaced the legacy Settings intent and lost the profile media fallback).
- A non-trivial change is reviewed by the other vendor's model than the one that wrote it
  (Claude's by Codex `gpt-6.2-sol` high, Codex's by the main session).
- Before starting any Codex review (EGM Goldfish reviewers included), ask the owner which Codex
  model and effort to use, with the table's choice as the recommendation (owner, 2026-11-26).
- When Codex is out of limits, do not start an Opus instance in its place: run the Claude
  reviewers that are due, mark the Codex one "deferred (Codex limit)" and tell the owner. A Claude
  review runs instead only when the owner asks for it, and is recorded as a Claude review.
- Nothing in this table is measured on Tatrak yet; it rests on a few tasks per model. Record in
  `knowledge/log.md` when a choice fails, and change the table.

## EGM reviews

Who runs each Goldfish reviewer of `/egm:egm-review` and how it stays isolated. Probes and
evidence: `knowledge/ops/egm-review-routing.md`.

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
  and web search, and keeps the sandbox read-only; Codex's own agent tools may remain (see the
  runbook), so the brief forbids them. The brief goes in a file and must
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

  For a Goldfish run this replaces the reporting rule above: the owner sees the tab, the Elephant
  reads `<report.md>`. A non-zero exit or a missing report file means `not run`.

## After every piece of work

1. Build and run the **full** unit-test suite (unit + Robolectric/Roborazzi screenshots) and look
   at the affected screenshots in `build/outputs/roborazzi/`.
2. Update `knowledge/`: the concept pages touched, `knowledge/project/backlog.md` /
   `roadmap.md` for planned work, `knowledge/project/risks.md`, an entry in `knowledge/log.md`,
   and `knowledge/index.md` for new pages. The bundle follows OKF v0.2 (front matter with
   `type`, `title`, `description`, `tags`, `status`, `generated`, `sources`).
3. When the owner asks to implement first and check on the emulator later, don't run emulator
   checks until asked; list what is unchecked in `risks.md`.

Keep test time down (owner, 2026-11-13; one full suite with `--rerun` takes minutes, and it was
being run after every one-line fix):

- While fixing, run only the affected tests (`--tests '<class or package>'`) and without
  `--rerun`: Gradle reruns what changed. The full suite and the debug build run once, when the
  piece of work is done and before it is committed.
- Emulator checks run once per step, after all review fixes are in, not after each fix. The
  exception: a fix that depends on how a library or Android behaves (paths behind a link, what a
  library accepts) is checked on the emulator at once; steps 5 and 6 broke exactly there.
- Emulator checks follow the cost rules in `knowledge/ops/test-environment.md` ("Keeping agent
  emulator checks cheap"): small or cropped screenshots, grepped dumps and logs, frames measured
  by a script, one scenario per agent; a brief for an emulator agent points to that section.
- Benchmarks run only when the owner asks for them.

## Bugs and performance

- Find the root cause before fixing: reproduce, measure, then change one thing. Remove temporary
  probes afterwards.
- Judge speed on the `benchmark` build, not on debug (debug is 3–4× slower); see
  `knowledge/build/build-setup.md`.

## Environment and commands

Every environment or tooling setup (SDK, emulator, test server, containers, scripts) gets a
runbook in `knowledge/ops/` or `knowledge/build/`, and its config files live in the repo (e.g.
`tools/test-server/`), never only in `/tmp`. Runbooks: `knowledge/build/build-setup.md`,
`knowledge/ops/test-environment.md`.

```sh
# JDK 25 lives in ~/.jdks on the dev machine, or in /usr/lib/jvm elsewhere: use whichever exists
for j in ~/.jdks/jdk-25* /usr/lib/jvm/java-25-openjdk; do [ -d "$j" ] && export JAVA_HOME=$j && break; done
./gradlew -q compileConversationsFreeDebugJavaWithJavac 2>&1 | grep -E "^e:|error:"   # compile check
./gradlew -q testConversationsFreeDebugUnitTest      # all tests; add --rerun if a parallel run broke the results dir
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' \
  build/test-results/testConversationsFreeDebugUnitTest/*.xml \
  | awk -F'"' '{t+=$2;f+=$6;e+=$8} END{print "tests",t,"failures",f,"errors",e}'
./gradlew -q assembleConversationsFreeDebug          # APKs in build/outputs/apk/conversationsFree/debug/ (x86_64 for the emulator)
./gradlew assembleConversationsFreeBenchmark         # optimised build for speed checks
```

- Android SDK is in `~/Android/Sdk`: set `ANDROID_HOME=~/Android/Sdk` (the shell's `/opt/android-sdk` is
  an empty root-owned SDK and breaks the emulator; with `~/Android` lint vital fails with an
  `Instantiatable` error on every class); adb is `~/Android/Sdk/platform-tools/adb`.
- When asked for release APKs, deliver only the signed `arm64-v8a` APK unless the owner asks for
  other variants, not merely unsigned Gradle outputs. The release key's password is never
  stored, so only the owner signs with it; an agent signs separate local test APKs with the
  existing Android debug key and labels them debug-signed. Verify each signature
  with `apksigner verify --verbose --print-certs` and check signing-key compatibility before
  claiming it will update an installed app. Never delete app data to resolve a signature mismatch.
  See `knowledge/build/build-setup.md` for the commands and limitations.
- Emulators: logged in as `tester@10.0.2.2` / `secret123` against the Prosody test server in
  podman (`tatrak-prosody`). Which emulators a machine has (serial, AVD, API level) is kept in
  that machine's agent memory, never in git.
  Messages from the other side:
  `podman exec tatrak-prosody python3 /send.py friend tester "text"`.
- Don't use `pkill -f` or `pgrep -f` with a pattern that also matches its own command line:
  write the pattern as `[G]radleWrapperMain`, so the shell running it does not match itself (on
  2026-11-20 a wait loop with the plain pattern waited for itself for 45 minutes).
- Every shell command an agent runs has a time limit (owner, 2026-11-20): a `timeout` on waits,
  loops and long scripts, and the tool's own timeout on Gradle runs; never an unbounded wait.
