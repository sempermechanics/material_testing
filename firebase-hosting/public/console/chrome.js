// The footer year. Its own file because the console CSP is script-src
// 'self' — an inline one-liner would be the only script on the page that
// never ran.
document.getElementById("year").textContent = String(new Date().getFullYear());
