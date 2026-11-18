import { createContext, useCallback, useContext, useMemo, useRef, useState } from 'react';
import { AlertTriangle, CheckCircle2, Info, X, XCircle } from 'lucide-react';

const ToastContext = createContext(null);

const KIND = {
  success: { icon: CheckCircle2, ring: 'border-ok/40', tone: 'text-ok' },
  error: { icon: XCircle, ring: 'border-crit/50', tone: 'text-crit' },
  warn: { icon: AlertTriangle, ring: 'border-warn/50', tone: 'text-warn' },
  info: { icon: Info, ring: 'border-cyan/40', tone: 'text-cyan' },
};

export function ToastProvider({ children }) {
  const [toasts, setToasts] = useState([]);
  const nextId = useRef(1);

  const dismiss = useCallback((id) => setToasts((t) => t.filter((x) => x.id !== id)), []);

  const push = useCallback(
    (kind, title, body, ms) => {
      const id = nextId.current++;
      setToasts((t) => [...t.slice(-3), { id, kind, title, body }]);
      const life = ms ?? (kind === 'error' ? 7000 : 4500);
      setTimeout(() => dismiss(id), life);
      return id;
    },
    [dismiss],
  );

  const api = useMemo(
    () => ({
      success: (title, body) => push('success', title, body),
      error: (title, body) => push('error', title, body),
      warn: (title, body) => push('warn', title, body),
      info: (title, body, ms) => push('info', title, body, ms),
      dismiss,
    }),
    [push, dismiss],
  );

  return (
    <ToastContext.Provider value={api}>
      {children}
      <div className="pointer-events-none fixed bottom-5 right-5 z-[60] flex w-[min(24rem,calc(100vw-2rem))] flex-col gap-2" role="region" aria-label="Notifications" aria-live="polite">
        {toasts.map((t) => {
          const k = KIND[t.kind];
          const Icon = k.icon;
          return (
            <div key={t.id} className={`rise pointer-events-auto flex items-start gap-3 rounded-xl border ${k.ring} bg-ink-800/95 p-3.5 shadow-2xl shadow-black/50 backdrop-blur`}>
              <Icon className={`mt-0.5 h-5 w-5 shrink-0 ${k.tone}`} aria-hidden />
              <div className="min-w-0 flex-1">
                <p className="text-[15px] font-medium leading-snug text-fg">{t.title}</p>
                {t.body && <p className="mt-0.5 text-sm leading-snug text-fg-soft">{t.body}</p>}
              </div>
              <button type="button" onClick={() => dismiss(t.id)} className="rounded-md p-1 text-fg-mute transition hover:bg-ink-600 hover:text-fg" aria-label="Dismiss notification">
                <X className="h-4 w-4" />
              </button>
            </div>
          );
        })}
      </div>
    </ToastContext.Provider>
  );
}

export const useToast = () => {
  const ctx = useContext(ToastContext);
  if (!ctx) throw new Error('useToast must be used inside ToastProvider');
  return ctx;
};
