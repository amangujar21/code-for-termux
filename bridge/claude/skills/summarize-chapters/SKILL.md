---
name: summarize-chapters
description: Create the continuity summaries in summaries/ and the fact sheet bible/continuity.md for chapters that don't have them, e.g. a novel started before this workflow. Use when the user asks to summarize or backfill chapters, or when /write-chapter finds summaries missing.
argument-hint: [chapter range, default all missing]
---

Backfill continuity records for: $ARGUMENTS (if empty, every chapter in `chapters/` with no matching `summaries/Chapter_NN.md`).

Find the novel folder as `/write-chapter` does. If its chapters live somewhere other than `chapters/` (e.g. `drafts/`), ask whether to move them to `chapters/` first; the other skills expect that folder.

Don't read chapter prose yourself.

1. Split the chapters into batches of about 8, in order, and start one `chapter-summarizer` agent per batch to summarize them, giving each the novel folder path and its list of chapter files. Batches can run in parallel.
2. When all batches are done, start one more `chapter-summarizer` agent to build the continuity sheet (`bible/continuity.md`) from all the summaries.
3. Tell the user how many summaries were written and pass on any contradictions the agents found.
