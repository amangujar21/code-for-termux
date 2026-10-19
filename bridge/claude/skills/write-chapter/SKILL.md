---
name: write-chapter
description: Write the next novel chapter (or a given one) from a premise or chapter prompt, in a fresh chapter-writer subagent so this session's context stays small. Use when the user asks to write or draft a chapter, e.g. "/write-chapter Ananya finds the letter", "/write-chapter 12 <premise>" or "/write-chapter 12-14".
argument-hint: [chapter number or range] [premise]
---

Write a chapter. Arguments: $ARGUMENTS

You are the director. **Never read or write chapter prose in this session**; prose fills the context. Only the `chapter-writer` subagent touches chapter text, summaries and `bible/continuity.md`.

## Find the novel

The novel folder is the one containing `bible/` and `chapters/`. Use the current folder if it has them. Otherwise look one and two levels down; if there's exactly one novel, use it, and if there are several, ask which. If there's none, suggest `/new-novel`.

## Work out the chapter and its prompt

- If the arguments start with a number or range (`12`, `12-14`), those are the chapters. Otherwise it's the next chapter: one more than the highest-numbered file in `chapters/`, or 1 if there are none.
- Everything after the number is the author's premise for the chapter. Save it verbatim to `prompts/Chapter_NN.md` (NN zero-padded) under a `# Chapter N` heading. If that file already exists, append the premise under `## Additional notes` instead of replacing what's there.
- With no premise and no prompt file, check whether `bible/outline.md` has a section for the chapter. If it does, ask whether to write from the outline alone; if not, ask for a premise.
- For a range, each chapter needs its own prompt file or outline section. Ask for any that are missing before starting.

## Write

For each chapter, in order and one at a time (each depends on the previous chapter's summary):

1. Check that every earlier chapter in `chapters/` has a matching `summaries/Chapter_NN.md`. If any are missing, follow `/summarize-chapters` for those first.
2. If `chapters/Chapter_NN*.md` already exists, ask before overwriting it.
3. Start a new `chapter-writer` agent (a fresh one for every chapter, never one resumed from earlier) with this prompt, filled in:

   > Write chapter N. The novel folder is <absolute path>. The chapter prompt is `prompts/<file>` (or: there is no prompt file; write from the outline section for this chapter). The previous chapter is `chapters/<file>` (or: this is the first chapter). [Any extra instructions the user gave.]

4. Relay the agent's report in a line or two, with the chapter's path so the user can open it in Obsidian. If it reports a continuity conflict or something it couldn't do, raise it before writing the next chapter.

When done, remind the user they can `/clear` before the next chapter; everything the next one needs is in the files.
