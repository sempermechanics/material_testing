// Finds the Firestore collection names a Python tree uses, for the guard in
// collections.test.mjs. Deliberately a grep, not a parser: it reads
// `.collection(<arg>)` / `.collection_group(<arg>)` calls and
// `.document("<name>/...")` paths. A string literal is taken as is; a bare
// name (`_INVITES`) or an attribute (`deletion.DELETED`) is resolved from a
// `NAME = "value"` assignment in the same file, then anywhere in the tree.
// Anything else (an f-string, a call) is reported as unresolved, which the
// guard treats as a failure: a collection name the scan cannot read is one
// the rules tests cannot cover.
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';

const CALL = /\.(collection|collection_group)\(\s*([^),]+?)\s*[),]/g;
const DOC_PATH = /\.document\(\s*f?(["'])([A-Za-z0-9_-]+)\//g;
const ASSIGN = /^[ \t]*([A-Za-z_][A-Za-z0-9_]*)[ \t]*(?::[^=\n]+)?=[ \t]*(["'])([A-Za-z0-9_-]+)\2[ \t]*(?:#.*)?$/gm;
const STRING = /^(["'])([^"']*)\1$/;
const NAME = /^(?:[A-Za-z_][A-Za-z0-9_]*\.)*([A-Za-z_][A-Za-z0-9_]*)$/;

/** Every .py file under `root`, sorted, skipping caches. */
export function pythonFiles(root) {
  const out = [];
  const walk = (dir) => {
    for (const entry of readdirSync(dir).sort()) {
      if (entry === '__pycache__' || entry.startsWith('.')) continue;
      const full = join(dir, entry);
      if (statSync(full).isDirectory()) walk(full);
      else if (entry.endsWith('.py')) out.push(full);
    }
  };
  walk(root);
  return out;
}

/** Strip `#` comments so a commented-out call is not counted. Naive about
 * `#` inside strings, which is fine for collection arguments. */
function stripComments(text) {
  return text.replace(/(^|[^"'\\])#[^\n]*/g, '$1');
}

/** NAME -> value for every `NAME = "value"` in `text`. */
export function constants(text) {
  const out = new Map();
  for (const m of text.matchAll(ASSIGN)) out.set(m[1], m[3]);
  return out;
}

/**
 * Scan `sources` ({file, text}[]) and return
 * { names: Map<name, string[] (file:line)>, unresolved: string[] }.
 */
export function scanSources(sources) {
  const global = new Map();
  for (const { text } of sources) {
    for (const [k, v] of constants(text)) if (!global.has(k)) global.set(k, v);
  }
  const names = new Map();
  const unresolved = [];
  const add = (name, where) => {
    if (!names.has(name)) names.set(name, []);
    names.get(name).push(where);
  };
  for (const { file, text: raw } of sources) {
    const text = stripComments(raw);
    const local = constants(text);
    const lineOf = (idx) => text.slice(0, idx).split('\n').length;
    for (const m of text.matchAll(CALL)) {
      const arg = m[2].trim();
      const where = `${file}:${lineOf(m.index)}`;
      const lit = STRING.exec(arg);
      if (lit) {
        add(lit[2], where);
        continue;
      }
      const ident = NAME.exec(arg);
      const key = ident && ident[1];
      const value = key && (local.get(key) ?? global.get(key));
      if (value) add(value, where);
      else unresolved.push(`${where}: .${m[1]}(${arg})`);
    }
    for (const m of text.matchAll(DOC_PATH)) {
      add(m[2], `${file}:${lineOf(m.index)}`);
    }
  }
  return { names, unresolved };
}

/** Scan a directory tree of Python files. Paths are reported relative to `base`. */
export function scanTree(root, base = root) {
  const sources = pythonFiles(root).map((file) => ({
    file: relative(base, file),
    text: readFileSync(file, 'utf8'),
  }));
  return { files: sources.length, ...scanSources(sources) };
}
