// Stand-in for https://www.gstatic.com/firebasejs/<ver>/firebase-auth.js.
//
// Exports exactly the names console/auth.js imports, with the behaviour auth.js
// relies on and nothing more. A test scripts it through `fake`: who is signed
// in, which factors they enrolled, whether this session's token records a
// second factor, how old the sign-in is, and what a redirect or a
// re-authentication does. Every SDK call is appended to `fake.calls` as
// [name, ...args] so a test can assert what auth.js asked Firebase to do.

const MFA_REQUIRED = "auth/multi-factor-auth-required";

export class FakeUser {
  constructor({
    uid = "uid-1",
    email = "operator@example.com",
    providers = ["google.com"],
    password = undefined,
    factors = [],
    secondFactor = false,
    authAgeSeconds = 10,
  } = {}) {
    this.uid = uid;
    this.email = email;
    this.providerData = providers.map((providerId) => ({ providerId, uid, email }));
    this.password = password;
    this.factors = factors.map((f) => ({ uid: "totp-1", factorId: "totp", ...f }));
    this.secondFactor = secondFactor;
    this.authTime = Date.now() - authAgeSeconds * 1000;
    // Firebase serves a cached ID token until asked to refresh: only
    // getIdToken(true) moves to the next version.
    this.tokenVersion = 1;
  }

  /** A completed re-authentication: new auth_time, and a second factor if one was proved. */
  freshen({ secondFactor = this.secondFactor } = {}) {
    this.authTime = Date.now();
    this.secondFactor = secondFactor;
  }

  get token() {
    return `token-${this.uid}-v${this.tokenVersion}`;
  }

  async getIdToken(forceRefresh = false) {
    fake.calls.push(["getIdToken", this.uid, Boolean(forceRefresh)]);
    if (forceRefresh) this.tokenVersion += 1;
    return this.token;
  }

  async getIdTokenResult() {
    const firebase = { sign_in_provider: this.providerData[0]?.providerId };
    if (this.secondFactor) firebase.sign_in_second_factor = "totp";
    return { authTime: new Date(this.authTime).toUTCString(), claims: { firebase } };
  }
}

/** The error Firebase raises when a sign-in or re-auth needs the second factor. */
function mfaError({ user, hints = [{ uid: "totp-1", factorId: "totp" }], reject = null } = {}) {
  const error = new Error("Multi-factor authentication required");
  error.code = MFA_REQUIRED;
  error.resolver = {
    hints,
    async resolveSignIn(assertion) {
      fake.calls.push(["resolveSignIn", assertion]);
      if (reject) throw Object.assign(new Error(reject), { code: reject });
      const who = user || fake.auth.currentUser;
      who.freshen({ secondFactor: true });
      return { user: who };
    },
  };
  return error;
}

const defaults = () => ({
  calls: [],
  listeners: [],
  // What getRedirectResult yields on this page load: null (not a return leg),
  // { user }, or an Error to throw (e.g. fake.mfaError()).
  redirectResult: null,
  onSignInWithRedirect: async () => {},
  // Resolving means the browser is on its way to Google.
  onReauthWithRedirect: async () => {},
  onReauthWithCredential: async (user, credential) => {
    if (user.password !== undefined && credential.password !== user.password) {
      throw Object.assign(new Error("invalid credential"), { code: "auth/invalid-credential" });
    }
    if (user.factors.length) throw mfaError({ user });
    user.freshen();
  },
  onEnroll: async (user, assertion) => {
    if (assertion.code !== "123456") {
      throw Object.assign(new Error("bad code"), { code: "auth/invalid-verification-code" });
    }
    user.factors.push({ uid: "totp-new", factorId: "totp", displayName: "Authenticator app" });
  },
});

export const fake = {
  auth: null,
  ...defaults(),
  mfaError,
  FakeUser,

  reset() {
    Object.assign(this, defaults());
    if (this.auth) this.auth.currentUser = null;
  },

  /** Make `user` current (null signs out) and tell every auth listener. */
  setUser(user) {
    this.auth.currentUser = user;
    this.notify();
  },

  notify() {
    for (const listener of [...this.listeners]) listener(this.auth.currentUser);
  },

  /** The calls to one SDK function, args only. */
  callsTo(name) {
    return this.calls.filter((c) => c[0] === name).map((c) => c.slice(1));
  },
};

export function getAuth(app) {
  fake.auth = { app, currentUser: null };
  return fake.auth;
}

export class GoogleAuthProvider {
  providerId = "google.com";
}

export class EmailAuthProvider {
  static credential(email, password) {
    return { providerId: "password", email, password };
  }
}

export async function signInWithRedirect(auth, provider) {
  fake.calls.push(["signInWithRedirect", provider.providerId]);
  return fake.onSignInWithRedirect(auth, provider);
}

export async function reauthenticateWithRedirect(user, provider) {
  fake.calls.push(["reauthenticateWithRedirect", user.uid, provider.providerId]);
  return fake.onReauthWithRedirect(user, provider);
}

export async function reauthenticateWithCredential(user, credential) {
  fake.calls.push(["reauthenticateWithCredential", user.uid, credential]);
  return fake.onReauthWithCredential(user, credential);
}

export async function updateCurrentUser(auth, user) {
  fake.calls.push(["updateCurrentUser", user && user.uid]);
  fake.setUser(user);
}

export async function getRedirectResult() {
  fake.calls.push(["getRedirectResult"]);
  const result = fake.redirectResult;
  if (result instanceof Error) throw result;
  return result;
}

export async function signOut() {
  fake.calls.push(["signOut"]);
  fake.setUser(null);
}

/** Like Firebase: the listener hears the current state once, asynchronously, then every change. */
export function onAuthStateChanged(auth, listener) {
  fake.listeners.push(listener);
  queueMicrotask(() => {
    if (fake.listeners.includes(listener)) listener(auth.currentUser);
  });
  return () => {
    fake.listeners = fake.listeners.filter((l) => l !== listener);
  };
}

export function multiFactor(user) {
  return {
    enrolledFactors: user.factors,
    async getSession() {
      fake.calls.push(["getSession", user.uid]);
      return { uid: user.uid };
    },
    async enroll(assertion, displayName) {
      fake.calls.push(["enroll", assertion, displayName]);
      return fake.onEnroll(user, assertion, displayName);
    },
  };
}

export function getMultiFactorResolver(auth, error) {
  fake.calls.push(["getMultiFactorResolver", error.code]);
  return error.resolver;
}

export const TotpMultiFactorGenerator = {
  FACTOR_ID: "totp",
  assertionForSignIn(enrollmentId, code) {
    return { kind: "signIn", enrollmentId, code };
  },
  async generateSecret(session) {
    fake.calls.push(["generateSecret", session.uid]);
    return {
      secretKey: "TESTSECRETBASE32",
      generateQrCodeUrl(account, issuer) {
        return `otpauth://totp/${encodeURIComponent(issuer)}:${encodeURIComponent(account)}` +
          `?secret=TESTSECRETBASE32&issuer=${encodeURIComponent(issuer)}`;
      },
    };
  },
  assertionForEnrollment(secret, code) {
    return { kind: "enrol", secretKey: secret.secretKey, code };
  },
};
