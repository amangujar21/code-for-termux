// Flag overused phrases and AI-prose habits in a chapter.
//
// Usage: node style_check.mjs <chapter.md> [banned_phrases.txt]
//
// Rules file format, one rule per line ("#" starts a comment):
//     phrase | max_per_chapter      (max defaults to 0)
//     @emdash_per_500 | 1           (em-dash limit per 500 words)
import { readFileSync } from 'node:fs';

// Defaults applied when the novel has no rules file, or doesn't override them.
const DEFAULT_RULES = {
  'closed her eyes': 1, 'closed his eyes': 1,
  'let out a breath': 0, 'released a breath': 0, "breath she didn't know": 0,
  "breath he didn't know": 0, 'clenched': 1, 'jaw tightened': 1,
  'a testament to': 0, 'something shifted': 0, "couldn't quite": 1,
  'for a long moment': 1, 'suddenly': 1, 'seemed to': 2,
  'half-step': 1, 'the weight of': 1, 'in that moment': 0,
};
const DEFAULT_EMDASH_PER_500 = 2;

function loadRules(path) {
  const rules = { ...DEFAULT_RULES };
  let emdash = DEFAULT_EMDASH_PER_500;
  if (!path) return { rules, emdash };
  for (const raw of readFileSync(path, 'utf8').split(/\r?\n/)) {
    const line = raw.split('#')[0].trim();
    if (!line) continue;
    const [phrase, limit = ''] = line.split('|').map((s) => s.trim());
    const max = Number(limit) || 0;
    if (phrase.toLowerCase() === '@emdash_per_500') emdash = max;
    else rules[phrase.toLowerCase()] = max;
  }
  return { rules, emdash };
}

const escape = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

const [chapterPath, rulesPath] = process.argv.slice(2);
if (!chapterPath) {
  console.error('Usage: node style_check.mjs <chapter.md> [banned_phrases.txt]');
  process.exit(1);
}
const text = readFileSync(chapterPath, 'utf8')
  .replace(/\r\n/g, '\n')
  .replace(/^---\n[\s\S]*?\n---\n/, '');
const { rules, emdash } = loadRules(rulesPath);
const lower = text.toLowerCase();
const words = (text.match(/[\p{L}\p{N}']+/gu) ?? []).length;
const problems = [];

for (const [phrase, max] of Object.entries(rules).sort()) {
  const n = (lower.match(new RegExp(`\\b${escape(phrase)}\\b`, 'g')) ?? []).length;
  if (n > max) problems.push(`'${phrase}': ${n} (max ${max})`);
}

const dashes = (text.match(/—| -- /g) ?? []).length;
const allowed = (emdash * words) / 500;
if (dashes > allowed) problems.push(`em-dashes: ${dashes} (max ${Math.round(allowed)} for ${words} words)`);

// Showing an emotion, then naming it in the next sentence.
const told = /[^.!?\n]+[.!?]\s+(?:She|He|They|I) (?:was|felt) (?:so )?(?:afraid|scared|angry|furious|nervous|sad|embarrassed|jealous|anxious)\b[^.!?]*[.!?]/g;
for (const m of text.matchAll(told)) {
  problems.push(`told emotion after showing it: "${m[0].trim().slice(0, 110)}"`);
}

// Paragraphs that open with the same word over and over.
const paras = text.split(/\n\s*\n/).map((p) => p.trim()).filter((p) => p && !p.startsWith('#'));
const openers = {};
for (const p of paras) {
  const w = p.match(/^[\p{L}\p{N}']+/u)?.[0];
  if (w) openers[w] = (openers[w] ?? 0) + 1;
}
for (const [w, n] of Object.entries(openers).sort((a, b) => b[1] - a[1]).slice(0, 3)) {
  if (n >= 6 && n / paras.length > 0.2) problems.push(`${n} of ${paras.length} paragraphs start with '${w}'`);
}

console.log(`${words} words`);
console.log(problems.length ? problems.map((p) => `- ${p}`).join('\n') : 'No issues found.');
