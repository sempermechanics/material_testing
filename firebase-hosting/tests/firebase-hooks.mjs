// Module resolution hooks for the console tests, registered by harness.mjs.
//
// auth.js imports the Firebase SDK straight from www.gstatic.com. Node cannot
// load an https: module at all, and a test must not touch the network anyway,
// so the two SDK URLs resolve to the local fakes beside this file. Any other
// remote import is refused loudly: a new one needs a fake of its own.
const FAKES = {
  "firebase-app.js": new URL("./fakes/firebase-app.mjs", import.meta.url).href,
  "firebase-auth.js": new URL("./fakes/firebase-auth.mjs", import.meta.url).href,
};
const SDK = /^https:\/\/www\.gstatic\.com\/firebasejs\/[\d.]+\/(firebase-(?:app|auth)\.js)$/;

export async function resolve(specifier, context, nextResolve) {
  const sdk = SDK.exec(specifier);
  if (sdk) return { url: FAKES[sdk[1]], shortCircuit: true };
  if (/^https?:/.test(specifier)) {
    throw new Error(`console tests make no network imports; add a fake for ${specifier}`);
  }
  return nextResolve(specifier, context);
}

// The console's .js files are ES modules the browser loads with
// type="module"; there is no package.json to say so. Name the format rather
// than leave Node to detect it (and warn) for every file.
const CONSOLE = new URL("../public/console/", import.meta.url).href;

export async function load(url, context, nextLoad) {
  if (url.startsWith(CONSOLE) && /\.js(\?|$)/.test(url)) {
    return nextLoad(url, { ...context, format: "module" });
  }
  return nextLoad(url, context);
}
