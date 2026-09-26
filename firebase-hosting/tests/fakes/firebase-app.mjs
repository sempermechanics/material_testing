// Stand-in for https://www.gstatic.com/firebasejs/<ver>/firebase-app.js.
// auth.js only calls initializeApp; the config it passed is kept for asserts.
export const apps = [];

export function initializeApp(options) {
  const app = { options };
  apps.push(app);
  return app;
}
