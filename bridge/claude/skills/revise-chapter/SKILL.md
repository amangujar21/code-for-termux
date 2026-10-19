---
name: revise-chapter
description: Revise an existing novel chapter from the author's notes, via a fresh chapter-writer subagent. Use when the user gives feedback on a chapter, e.g. "/revise-chapter 7 make the argument meaner and cut the flashback".
argument-hint: <chapter number> <notes>
---

Revise a chapter: $ARGUMENTS

The first number is the chapter; the rest are the author's notes. You are the director. Don't read the chapter prose yourself.

1. Find the novel folder as `/write-chapter` does, then `chapters/Chapter_NN*.md`. If the notes are missing or too vague to act on, ask for them.
2. If a note is a lasting decision about the story (not just this chapter's wording), also record it in the right `bible/` file.
3. Start a new `chapter-writer` agent:

   > Revise chapter N at `chapters/<file>`. The novel folder is <absolute path>. Its prompt is `prompts/<file>` (if one exists). Author's notes, verbatim: "<notes>".

4. Relay the report in a line or two. If events changed, tell the user which later chapters may now contradict it.
