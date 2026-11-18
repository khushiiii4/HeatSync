import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { configureAuth } from '../api/client';
import { login as apiLogin } from '../api/endpoints';

// Phase 8 ships a login stub (plan roadmap): the real endpoint POST /api/auth/login is tried first; because the
// gateway runs with thermogrid.security.enabled=false until Phase 9, a demo session can be started instead. In
// demo mode no token is sent and the role only controls what the UI shows. Phase 9 turns enforcement on at the
// gateway without changing this file's contract: the JWT is kept in memory and in localStorage (plan 9.6).
const STORAGE_KEY = 'thermogrid.auth';
const AuthContext = createContext(null);

function readStored() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    const v = JSON.parse(raw);
    return v?.user?.role ? v : null;
  } catch {
    return null;
  }
}

export function AuthProvider({ children, onForbidden }) {
  const [session, setSession] = useState(readStored);

  const persist = useCallback((s) => {
    setSession(s);
    if (s) localStorage.setItem(STORAGE_KEY, JSON.stringify(s));
    else localStorage.removeItem(STORAGE_KEY);
  }, []);

  const logout = useCallback(() => persist(null), [persist]);

  useEffect(() => {
    configureAuth({
      getToken: () => session?.token ?? null,
      onUnauthorized: () => persist(null), // a 401 returns the user to the login page
      onForbidden: (err) => onForbidden?.(err), // a 403 shows a toast
    });
  }, [session, persist, onForbidden]);

  const signIn = useCallback(
    async (username, password) => {
      const res = await apiLogin(username, password);
      persist({ token: res.token, user: res.user, stub: false });
      return res.user;
    },
    [persist],
  );

  const startDemoSession = useCallback(
    (role) => {
      persist({ token: null, user: { username: `demo-${role.toLowerCase()}`, role }, stub: true });
    },
    [persist],
  );

  const value = useMemo(
    () => ({ user: session?.user ?? null, role: session?.user?.role ?? null, stub: !!session?.stub, signIn, startDemoSession, logout }),
    [session, signIn, startDemoSession, logout],
  );
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export const useAuth = () => {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside AuthProvider');
  return ctx;
};
