// Converts the downloaded open-source data into compact keyboard assets.
// Usage: node scripts/build_assets.js <data-dir> <assets-dir>
//
// Sources (see NOTICE.md):
//   ibus-avro (MPL-2.0): avrolib.js, avroregexlib.js, avrodict.js, suffixdict.js, autocorrect.js
//   AOSP / OpenBoard en_US wordlist (Apache-2.0): main_en_US.combined
//   Unicode emoji-test.txt
"use strict";
const fs = require("fs");
const path = require("path");
const [dataDir, outDir] = process.argv.slice(2);
if (!dataDir || !outDir) { console.error("usage: build_assets.js <data> <assets>"); process.exit(1); }
fs.mkdirSync(path.join(outDir, "avro"), { recursive: true });

// The source files are JS, but the data in them is a JSON object literal. We never execute
// them: find the object that follows `marker`, cut it out by brace matching, and JSON.parse it.
function extractJson(file, marker) {
  const src = fs.readFileSync(path.join(dataDir, file), "utf8");
  const at = src.indexOf(marker);
  if (at < 0) throw new Error(marker + " not found in " + file);
  const start = src.indexOf("{", at + marker.length);
  let depth = 0, inStr = false, esc = false;
  for (let i = start; i < src.length; i++) {
    const c = src[i];
    if (inStr) { if (esc) esc = false; else if (c === "\\") esc = true; else if (c === '"') inStr = false; continue; }
    if (c === '"') inStr = true;
    else if (c === "{") depth++;
    else if (c === "}" && --depth === 0) return JSON.parse(src.slice(start, i + 1));
  }
  throw new Error("unbalanced object in " + file);
}
const evalData = (file, marker) => extractJson(file, marker);
const write = (rel, text) => { fs.writeFileSync(path.join(outDir, rel), text, "utf8"); console.log(rel, (Buffer.byteLength(text) / 1024).toFixed(0) + " KB"); };

// ---- Avro ----
const phonetic = evalData("avrolib.js", "data:");
write("avro/phonetic.json", JSON.stringify(phonetic));
const regex = evalData("avroregexlib.js", "data:");
write("avro/regex.json", JSON.stringify(regex));
write("avro/suffix.json", JSON.stringify(evalData("suffixdict.js", "var db")));
write("avro/autocorrect.json", JSON.stringify(evalData("autocorrect.js", "var db")));
const tables = evalData("avrodict.js", "var tables");
let bn = "";
let bnCount = 0;
for (const [name, words] of Object.entries(tables)) {
  bn += "#" + name + "\n" + words.join("\n") + "\n";
  bnCount += words.length;
}
write("avro/dict.txt", bn);
console.log("  bangla words:", bnCount);

// ---- English ----
const MAX_WORDS = 110000;
const lines = fs.readFileSync(path.join(dataDir, "main_en_US.combined"), "utf8").split("\n");
const words = []; // {w, f, off}
const shortcuts = [];
const bigrams = new Map(); // word -> [[next, f]]
let cur = null;
for (const line of lines) {
  if (line.startsWith(" word=")) {
    const kv = Object.fromEntries(line.trim().split(",").map(p => { const i = p.indexOf("="); return [p.slice(0, i), p.slice(i + 1)]; }));
    const w = kv.word;
    cur = w;
    if (kv.not_a_word === "true") continue;
    if (!/^[A-Za-z][A-Za-z']*$/.test(w)) continue;
    const f = parseInt(kv.f, 10) || 0;
    const flags = kv.flags || "";
    if (flags.includes("nonword")) continue;
    words.push({ w, f, off: flags.includes("offensive") });
  } else if (line.startsWith("  shortcut=") && cur) {
    const m = line.trim().match(/^shortcut=(.*),f=whitelist$/);
    if (m && /^[a-z]+$/.test(cur) && m[1].toLowerCase().replace(/'/g, "") === cur) shortcuts.push(cur + "\t" + m[1]);
  } else if (line.startsWith("  bigram=") && cur) {
    const m = line.trim().match(/^bigram=(.*),f=(\d+)$/);
    if (m && /^[A-Za-z']+$/.test(m[1])) {
      if (!bigrams.has(cur)) bigrams.set(cur, []);
      bigrams.get(cur).push([m[1], parseInt(m[2], 10)]);
    }
  }
}
words.sort((a, b) => b.f - a.f);
const kept = words.slice(0, MAX_WORDS);
const keptSet = new Set(kept.map(x => x.w));
// word \t freq(0..255) [\t o]   (o = offensive: never suggested unless typed exactly)
write("en_words.txt", kept.map(x => x.w + "\t" + x.f + (x.off ? "\to" : "")).join("\n") + "\n");
write("en_shortcuts.txt", [...new Set(shortcuts)].join("\n") + "\n");
// Bigrams for the 25k most frequent words, top 6 each.
let bg = "";
for (const x of kept.slice(0, 25000)) {
  const list = bigrams.get(x.w);
  if (!list) continue;
  const nexts = list.filter(([n]) => keptSet.has(n)).sort((a, b) => b[1] - a[1]).slice(0, 6).map(([n]) => n);
  if (nexts.length) bg += x.w + "\t" + nexts.join(" ") + "\n";
}
write("en_bigrams.txt", bg);
console.log("  english words:", kept.length, "of", words.length);

// ---- Emoji ----
const emojiLines = fs.readFileSync(path.join(dataDir, "emoji-test.txt"), "utf8").split("\n");
const groups = []; // {name, items: [{e, name, tones:[]}]}
let group = null;
const byName = new Map();
const TONE = /: (light|medium-light|medium|medium-dark|dark) skin tone$/;
for (const line of emojiLines) {
  const g = line.match(/^# group: (.*)$/);
  if (g) { group = { name: g[1].trim(), items: [] }; if (group.name !== "Component") groups.push(group); continue; }
  const m = line.match(/^([0-9A-F ]+?)\s*;\s*fully-qualified\s*#\s*(\S+)\s+E[\d.]+\s+(.*)$/);
  if (!m || !group || group.name === "Component") continue;
  const e = m[1].trim().split(/\s+/).map(h => String.fromCodePoint(parseInt(h, 16))).join("");
  const name = m[3].trim();
  const t = name.match(TONE);
  if (t) { const base = byName.get(name.replace(TONE, "")); if (base) base.tones.push(e); continue; }
  if (name.includes("skin tone")) continue; // multi-tone combos: skip
  const item = { e, name, tones: [] };
  byName.set(name, item);
  group.items.push(item);
}
let em = "";
for (const g of groups) {
  em += "@" + g.name + "\n";
  for (const it of g.items) em += it.e + (it.tones.length === 5 ? "\t" + it.tones.join(" ") : "") + "\n";
}
write("emoji.txt", em);

// ---- Bangla glide lexicon ----
// Source: wordfreq large_bn (CC BY-SA 4.0), a "cBpack" msgpack: [header, bin0, bin1, ...] where
// every word in bin i has frequency 10^(-i/100). We keep the most common pure-Bengali words and
// give each one up to 4 lower-case "swipe spellings" — what a person traces when gliding it in
// Banglish. Case is irrelevant to a swipe; the Bangla word itself carries ত vs ট.
const zlib = require("zlib");
function msgpackDecode(buf) {
  let p = 0;
  const str = n => { const s = buf.toString("utf8", p, p + n); p += n; return s; };
  const arr = n => { const a = new Array(n); for (let i = 0; i < n; i++) a[i] = read(); return a; };
  const map = n => { const o = {}; for (let i = 0; i < n; i++) { const k = read(); o[k] = read(); } return o; };
  function read() {
    const b = buf[p++];
    if (b <= 0x7f) return b;
    if (b >= 0xe0) return b - 256;
    if ((b & 0xf0) === 0x80) return map(b & 0x0f);
    if ((b & 0xf0) === 0x90) return arr(b & 0x0f);
    if ((b & 0xe0) === 0xa0) return str(b & 0x1f);
    switch (b) {
      case 0xc0: return null; case 0xc2: return false; case 0xc3: return true;
      case 0xcc: return buf[p++];
      case 0xcd: { const v = buf.readUInt16BE(p); p += 2; return v; }
      case 0xce: { const v = buf.readUInt32BE(p); p += 4; return v; }
      case 0xd9: return str(buf[p++]);
      case 0xda: { const n = buf.readUInt16BE(p); p += 2; return str(n); }
      case 0xdb: { const n = buf.readUInt32BE(p); p += 4; return str(n); }
      case 0xdc: { const n = buf.readUInt16BE(p); p += 2; return arr(n); }
      case 0xdd: { const n = buf.readUInt32BE(p); p += 4; return arr(n); }
      case 0xde: { const n = buf.readUInt16BE(p); p += 2; return map(n); }
    }
    throw new Error("msgpack type 0x" + b.toString(16) + " at " + (p - 1));
  }
  return read();
}

// Bangla -> swipe spelling options, each [spelling, cost]. Cost 0 = most common way to write it.
const CONS = {
  "ক": [["k", 0]], "খ": [["kh", 0]], "গ": [["g", 0]], "ঘ": [["gh", 0]], "ঙ": [["ng", 0]],
  "চ": [["c", 0], ["ch", 0]], "ছ": [["ch", 0], ["chh", 1]], "জ": [["j", 0], ["z", 1]], "ঝ": [["jh", 0]], "ঞ": [["n", 0]],
  "ট": [["t", 0]], "ঠ": [["th", 0]], "ড": [["d", 0]], "ঢ": [["dh", 0]], "ণ": [["n", 0]],
  "ত": [["t", 0]], "থ": [["th", 0]], "দ": [["d", 0]], "ধ": [["dh", 0]], "ন": [["n", 0]],
  "প": [["p", 0]], "ফ": [["f", 0], ["ph", 0]], "ব": [["b", 0]], "ভ": [["bh", 0], ["v", 0]], "ম": [["m", 0]],
  "য": [["j", 0], ["z", 0]], "র": [["r", 0]], "ল": [["l", 0]], "শ": [["sh", 0], ["s", 0]], "ষ": [["sh", 0], ["s", 0]],
  "স": [["s", 0], ["sh", 1]], "হ": [["h", 0]], "\u09dc": [["r", 0]], "\u09dd": [["rh", 0], ["r", 1]], "\u09df": [["y", 0], ["", 1]],
  "ৎ": [["t", 0]],
};
const KAR = {
  "া": [["a", 0]], "ি": [["i", 0]], "ী": [["i", 0], ["ee", 1]], "ু": [["u", 0]], "ূ": [["u", 0], ["oo", 1]],
  "ৃ": [["ri", 0]], "ে": [["e", 0]], "ৈ": [["oi", 0], ["ai", 1]], "ো": [["o", 0]], "ৌ": [["ou", 0], ["ow", 1]],
};
const VOWEL = {
  "অ": [["o", 0], ["a", 1]], "আ": [["a", 0], ["aa", 1]], "ই": [["i", 0]], "ঈ": [["i", 0], ["ee", 1]], "উ": [["u", 0]],
  "ঊ": [["u", 0]], "ঋ": [["ri", 0]], "এ": [["e", 0]], "ঐ": [["oi", 0]], "ও": [["o", 0]], "ঔ": [["ou", 0]],
};
const SIGN = { "ং": [["ng", 0]], "ঁ": [["", 0]], "ঃ": [["", 0], ["h", 1]] };
const HASANT = "্";

// Turn a word into a list of option-sets, then enumerate the cheapest combinations.
function slots(word) {
  const cs = [...word];
  const out = [];
  for (let i = 0; i < cs.length; i++) {
    const c = cs[i], next = cs[i + 1];
    if (CONS[c]) {
      // ্য (ya-phala) and ্ব (ba-phala) after a consonant are mostly silent in Banglish.
      if (out.length && cs[i - 1] === HASANT && (c === "য" || c === "ব")) out.push(c === "য" ? [["", 0], ["y", 1]] : [["", 0], ["b", 1]]);
      else out.push(CONS[c]);
      const after = next;
      if (after === HASANT || KAR[after]) continue;
      // Inherent vowel: usually spoken (and swiped) mid-word, usually silent at the end.
      if (after === undefined) out.push([["", 0], ["o", 1]]);
      else out.push([["o", 0], ["", 1]]);
    } else if (KAR[c]) out.push(KAR[c]);
    else if (VOWEL[c]) out.push(VOWEL[c]);
    else if (SIGN[c]) out.push(SIGN[c]);
    else if (c === HASANT) continue;
    else return null; // digits, punctuation, other scripts
  }
  return out;
}
function spellings(word, max = 4, maxCost = 2) {
  const s = slots(word);
  if (!s || !s.length) return [];
  const found = new Map();
  (function dfs(i, acc, cost) {
    if (found.size >= 64) return;
    if (i === s.length) {
      const sp = acc.replace(/(.)\1+/g, "$1"); // a swipe can't repeat a key
      if (sp.length >= 2 && (!found.has(sp) || found.get(sp) > cost)) found.set(sp, cost);
      return;
    }
    for (const [t, c] of s[i]) if (cost + c <= maxCost) dfs(i + 1, acc + t, cost + c);
  })(0, "", 0);
  return [...found.entries()].sort((a, b) => a[1] - b[1] || a[0].length - b[0].length).slice(0, max).map(e => e[0]);
}

const BN_GLIDE_MAX = 60000;
// Canonical Bangla form, the same one Avro types: precomposed ড় ঢ় য় (NFC splits them into
// letter + nukta), no zero-width joiners. Junk tokens are dropped.
function canonicalBn(w) {
  const c = w.normalize("NFC").replace(/[‌‍]/g, "")
    .replace(/ড়/g, "ড়").replace(/ঢ়/g, "ঢ়").replace(/য়/g, "য়");
  if (!/^[ঀ-৿]+$/.test(c)) return null;
  if (/ঃ$/.test(c)) return null;                       // trailing ঃ used as a colon
  if (/অা/.test(c)) return null;                  // অ + া instead of আ
  if (/[া-ৌৗ]{2}/.test(c)) return null;       // two vowel signs in a row
  if (/^[া-্ঁ-ঃ]/.test(c)) return null;  // starts with a sign
  return c;
}
const cb = msgpackDecode(zlib.gunzipSync(fs.readFileSync(path.join(dataDir, "large_bn.msgpack.gz"))));
let bnGlide = "";
let bnGlideCount = 0;
const bnSeen = new Set();
for (let bin = 1; bin < cb.length && bnGlideCount < BN_GLIDE_MAX; bin++) {
  const zipf = 9 - (bin - 1) / 100;             // wordfreq bins: freq = 10^(-i/100)
  const f = Math.max(0, Math.min(255, Math.round(zipf / 7 * 255)));
  for (const raw of cb[bin]) {
    if (bnGlideCount >= BN_GLIDE_MAX) break;
    const w = canonicalBn(raw);
    if (!w || bnSeen.has(w)) continue;   // same word in another encoding: keep the more frequent
    bnSeen.add(w);
    const sp = spellings(w);
    if (!sp.length) continue;
    bnGlide += w + "\t" + f + "\t" + sp.join(" ") + "\n";
    bnGlideCount++;
  }
}
write("bn_glide.txt", bnGlide);
console.log("  bangla glide words:", bnGlideCount);
