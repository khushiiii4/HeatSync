import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { configureAuth } from '../api/client';
import { login as apiLogin } from '../api/endpoints';

// Real authentication (plan 9.6): POST /api/auth/login returns a signed JWT. For this lab demo the token is kept in
// memory (React state) and in localStorage so a page reload keeps the session. The gateway is the real enforcement
// point: a 401 clears the session and returns to the login page, a 403 shows a toast.
export const STORAGE_KEY = 'thermogrid.auth';
const AuthContext = createContext(null);

function readStored(now = Date.now()) {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    const v = JSON.parse(raw);
    if (!v?.token || !v?.user?.role || !(v.expiresAt > now)) {
      localStorage.removeItem(STORAGE_KEY);
      return null;
    }
    return v;
  } catch {
    localStorage.removeItem(STORAGE_KEY);
    return null;
  }
}

export function AuthProvider({ children, onForbidden, onSessionEnded }) {
  const [session, setSession] = useState(readStored);
  const sessionRef = useRef(session);

  const persist = useCallback((s) => {
    sessionRef.current = s;
    setSession(s);
    if (s) localStorage.setItem(STORAGE_KEY, JSON.stringify(s));
    else localStorage.removeItem(STORAGE_KEY);
  }, []);

  const logout = useCallback(() => persist(null), [persist]);

  // The latest callbacks, without re-registering the interceptors on every render.
  const cb = useRef({ onForbidden, onSessionEnded });
  cb.current = { onForbidden, onSessionEnded };

  useEffect(() => {
    configureAuth({
      getToken: () => sessionRef.current?.token ?? null,
      onUnauthorized: () => {
        if (!sessionRef.current) return; // already signed out: a late response from an old request
        persist(null);
        cb.current.onSessionEnded?.('rejected');
      },
      onForbidden: (err) => cb.current.onForbidden?.(err),
    });
  }, [persist]);

  // Sign out by itself when the token expires, so the user is never left on a dashboard that can no longer load.
  useEffect(() => {
    if (!session) return undefined;
    const ms = Math.max(0, session.expiresAt - Date.now());
    const t = setTimeout(() => {
      persist(null);
      cb.current.onSessionEnded?.('expired');
    }, Math.min(ms, 2 ** 31 - 1));
    return () => clearTimeout(t);
  }, [session, persist]);

  const signIn = useCallback(
    async (username, password) => {
      const res = await apiLogin(username, password);
      persist({ token: res.token, user: res.user, expiresAt: Date.now() + (res.expiresInSeconds ?? 0) * 1000 });
      return res.user;
    },
    [persist],
  );

  const value = useMemo(() => ({ user: session?.user ?? null, role: session?.user?.role ?? null, signIn, logout }), [session, signIn, logout]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export const useAuth = () => {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside AuthProvider');
  return ctx;
};
