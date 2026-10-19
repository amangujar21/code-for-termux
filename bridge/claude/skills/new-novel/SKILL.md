---
name: new-novel
description: Set up a new novel project by interviewing the author about premise, characters, style and structure, then creating the folder, story bible and outline that /write-chapter uses. Use when the user wants to start a new novel or book, e.g. "/new-novel" or "/new-novel a spy thriller set in Mumbai".
argument-hint: [optional idea or title]
---

Set up a new novel. The author's starting idea, if any: $ARGUMENTS

You are interviewing the author, then building the project. Don't write any chapter prose.

## How to interview

- Ask in plain chat messages, one round at a time, 3 to 5 numbered questions per round. **Don't use the AskUserQuestion tool**: some clients this runs in can't answer it.
- Keep questions short. Where it helps, offer two or three concrete suggestions fitting what they've said so far, so they can reply "2" or "you pick".
- The author can answer "skip" or "you decide" to anything; make a sensible choice and say what you chose in the recap.
- If they paste existing material (a premise doc, character sheets, a style guide, an outline), use it, and skip questions it already answers.
- Skip anything the starting idea already answers. Don't ask for things you can infer.

## Rounds

1. **Basics**: working title; genre and subgenre; the premise in a few sentences; who the book is for; content limits (e.g. heat level, violence, language).
2. **Story**: the protagonist's goal, flaw and how they change; what stands in their way (antagonist, rival, circumstances); the ending, if they know it (for romance: HEA, HFN or other); the big secrets or twists, and when the reader should learn them; where and when it's set.
3. **Characters**: the main cast (usually 3 to 8). For each: name, role, age and a line on appearance; how they speak (rhythm, vocabulary, verbal tics, what they'd never say); how they show emotion (e.g. "deflects with humour, never goes silent"); how they relate to the others. Let the author describe them freely; ask follow-ups only for gaps that matter.
4. **Style**: POV (first or third, one character or several) and tense; two or three authors or books whose prose feel they want; sentence rhythm and how much introspection versus action; dialogue style; pet peeves and banned words or phrases; em-dash rule; how chapters should end (cliffhanger, quiet beat). Invite them to paste a short passage they love, theirs or anyone's, as a target.
5. **Structure**: number of chapters and words per chapter; whether they have an outline (paste it) or want you to draft one from everything so far.

Then post a short recap of the decisions (bullets, under 250 words) and ask once for corrections. Then ask where to create the project:

- On Android (`uname -o` prints `Android`, or `/storage/emulated/0` exists), suggest `/storage/emulated/0/Documents/Novels/<Title>` and ask if their Obsidian vault is somewhere else. Files inside Termux's own home can't be opened by Obsidian. If `/storage/emulated/0` can't be read, tell them to grant Termux storage access (run `termux-setup-storage` in Termux) and try again.
- Elsewhere, suggest `<current folder>/<Title>`.

## Build the project

Create this in the chosen folder (folder and file names without spaces in the title part are fine; use underscores):

```
<Title>/
  CLAUDE.md
  bible/characters.md
  bible/style_guide.md
  bible/world.md
  bible/outline.md
  bible/continuity.md
  bible/banned_phrases.txt
  prompts/
  chapters/
  summaries/
```

Put a `.gitkeep` in each empty folder so it exists on sync services. Write the files from the interview:

- **`CLAUDE.md`**: copy this, filling in the title:

  ```
  # <Title>

  This folder is one novel. The author reads it in Obsidian.

  - `bible/`: everything every chapter must respect. `continuity.md` is the running fact sheet, updated after every chapter.
  - `prompts/`: the author's chapter prompts and premises, `Chapter_NN.md`. They take priority over the outline.
  - `chapters/`: the manuscript, one file per chapter.
  - `summaries/`: a continuity summary per chapter, written by the agents.

  ## Rules for the main session

  - Never write or read chapter prose here. Use `/write-chapter`, `/revise-chapter` and `/summarize-chapters`, which hand prose work to a fresh subagent each time.
  - When the author makes a lasting decision in chat (a character trait, a plot change, a style rule), write it into the right `bible/` or `prompts/` file straight away and say which file. Chat is cleared between chapters; only the files carry over.
  - Never overwrite a chapter without asking.
  ```

- **`bible/characters.md`**: one `## Name` section per character: role, age and appearance, background (only what the story uses), goal and flaw, arc, **Voice** (speech patterns, vocabulary, tics, things they'd never say, with two or three sample lines of dialogue), **Emotional expression** rules, and relationships. Add a short voice-comparison table at the end if there are four or more characters.
- **`bible/style_guide.md`**: the binding rules for the prose, written as concrete instructions, not adjectives. Cover POV and tense, psychic distance, sentence rhythm, introspection versus action, dialogue (tags, banter, subtext), how emotion is shown, description and sensory focus, chapter length and chapter endings, and the author's pet peeves. Include a short do/don't example pair for the three or four most important rules, written in the target style. If the author pasted a passage, describe what makes it work and point to it. Keep it under about 1,500 words: every chapter agent reads it in full.
- **`bible/world.md`**: setting, time period, key places, and any rules (institutions, technology, magic, jobs, sports) the story must get right.
- **`bible/outline.md`**: one section per chapter in exactly this format, so the writer can read just the parts it needs:

  ```
  ## Chapter N: Title
  <one-line logline>

  - beat
  - beat
  ```

  If the author gave an outline, convert it to this format without changing its content. If you're drafting it, use a structure that suits the genre (e.g. a romance beat sheet, Save the Cat, a mystery's clue-and-reveal plan), place the secrets and twists from round 2, and give each chapter 3 to 6 beats. Before writing a drafted outline, post the chapter titles and loglines in chat for approval.
- **`bible/continuity.md`**: three sections. **Facts**: lasting facts fixed during setup (names, ages, relationships, backstory events), tagged `(setup)`. **Who knows what**: each secret and who knows it at the start of the book, including whether the reader does. **Open threads**: setups the outline plans to pay off, each with the chapter it pays off in.
- **`bible/banned_phrases.txt`**: the author's banned words and phrases, one per line as `phrase | max per chapter` (0 if fully banned), plus `@emdash_per_500 | N` from their em-dash rule (default 2). Start the file with this comment: `# phrase | max per chapter. The checker also has built-in limits for common AI phrasing.`

## Finish

Tell the author, in a few lines:

- where the project is, and to open that folder (or its parent vault) in Obsidian;
- to `/clear`, then start a session in the project folder (in the Android app: a new session in that folder) and run `/write-chapter <premise for chapter 1>`, or just `/write-chapter` to write chapter 1 from the outline;
- that anything they want changed later goes into the `bible/` files, which they can edit directly in Obsidian.
