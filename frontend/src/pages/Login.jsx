import { useState } from 'react';
import { Navigate, useNavigate } from 'react-router-dom';
import { LogIn, ShieldAlert } from 'lucide-react';
import { useAuth } from '../auth/AuthContext';
import { errorMessage } from '../api/client';
import { Wordmark } from '../components/Brand';
import { Button, Tag, cx, inputCls } from '../components/ui';
import { ROLES } from '../lib/roles';

const ROLE_HELP = {
  VIEWER: 'Read-only dashboards',
  OPERATOR: 'Adds workloads and the Control Room',
  ADMIN: 'Adds simulator control and seeding',
};

/** Decorative only: a quiet grid echoing the rack heat map. It carries no data. */
function GridMotif() {
  const tones = ['#1d4ed8', '#0f766e', '#0f766e', '#1d4ed8', '#0f766e', '#a16207', '#0f766e', '#0f766e', '#1d4ed8', '#0f766e', '#c2410c', '#a16207', '#1d4ed8', '#0f766e', '#0f766e', '#1d4ed8'];
  return (
    <div className="grid w-[300px] grid-cols-4 gap-2.5 opacity-90" aria-hidden>
      {tones.map((c, i) => (
        <div key={i} className="aspect-square rounded-lg" style={{ background: `linear-gradient(145deg, ${c}, ${c}99)`, opacity: 0.35 + ((i * 37) % 60) / 100 }} />
      ))}
    </div>
  );
}

export default function Login() {
  const { user, signIn, startDemoSession } = useAuth();
  const navigate = useNavigate();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState(null); // { kind: 'credentials' | 'unavailable', text }
  const [role, setRole] = useState('OPERATOR');

  if (user) return <Navigate to="/" replace />;

  const submit = async (e) => {
    e.preventDefault();
    setBusy(true);
    setProblem(null);
    try {
      await signIn(username.trim(), password);
      navigate('/', { replace: true });
    } catch (err) {
      const status = err?.response?.status;
      if (status === 401 || status === 400) setProblem({ kind: 'credentials', text: 'That username and password were not accepted.' });
      else setProblem({ kind: 'unavailable', text: `${errorMessage(err)}. The authentication service is delivered in Phase 9.` });
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="app-bg grid min-h-full lg:grid-cols-[1.05fr_1fr]">
      <div className="relative hidden flex-col justify-between overflow-hidden border-r border-ink-600/70 p-12 lg:flex">
        <Wordmark />
        <div className="flex flex-col gap-8">
          <GridMotif />
          <div className="max-w-md">
            <h2 className="text-[32px] font-semibold leading-tight tracking-tight text-fg">The data center that decides for itself — and shows its work.</h2>
            <p className="mt-3 text-base leading-relaxed text-fg-soft">
              Forecast hotspots a minute ahead, watch workloads move away from heat, and follow every cooling decision with its reason.
            </p>
          </div>
        </div>
        <p className="text-xs text-fg-faint">Simulated plant · all sensor activity is generated in software</p>
      </div>

      <div className="flex items-center justify-center p-6 sm:p-12">
        <div className="w-full max-w-[420px]">
          <div className="mb-8 lg:hidden">
            <Wordmark />
          </div>
          <h1 className="text-2xl font-semibold tracking-tight text-fg">Sign in</h1>
          <p className="mt-1.5 text-[15px] text-fg-mute">Use your ThermoGrid operator account.</p>

          <form onSubmit={submit} className="mt-7 space-y-4" noValidate>
            <div>
              <label htmlFor="username" className="mb-1.5 block text-sm font-medium text-fg-soft">
                Username
              </label>
              <input id="username" className={inputCls} autoComplete="username" value={username} onChange={(e) => setUsername(e.target.value)} required />
            </div>
            <div>
              <label htmlFor="password" className="mb-1.5 block text-sm font-medium text-fg-soft">
                Password
              </label>
              <input id="password" type="password" className={inputCls} autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} required />
            </div>
            {problem && (
              <p className={cx('rounded-lg border px-3 py-2.5 text-sm', problem.kind === 'credentials' ? 'border-crit/40 bg-crit/10 text-crit' : 'border-warn/40 bg-warn/10 text-warn')} role="alert">
                {problem.text}
              </p>
            )}
            <Button type="submit" variant="primary" size="lg" className="w-full" disabled={busy || !username || !password}>
              <LogIn className="h-4 w-4" aria-hidden /> {busy ? 'Signing in…' : 'Sign in'}
            </Button>
          </form>

          <div className="mt-8 rounded-xl border border-ink-600 bg-ink-850/80 p-4">
            <div className="mb-3 flex items-center justify-between gap-2">
              <p className="flex items-center gap-2 text-sm font-semibold text-fg">
                <ShieldAlert className="h-4 w-4 text-warn" aria-hidden /> Demo session
              </p>
              <Tag tone="warn">Until Phase 9</Tag>
            </div>
            <p className="mb-3 text-sm leading-relaxed text-fg-mute">
              The gateway runs with security switched off until Phase 9. Start a demo session to explore the dashboard; the role only changes what this UI shows.
            </p>
            <div className="mb-3 grid grid-cols-3 gap-2" role="radiogroup" aria-label="Demo role">
              {ROLES.map((r) => (
                <button
                  key={r}
                  type="button"
                  role="radio"
                  aria-checked={role === r}
                  onClick={() => setRole(r)}
                  className={cx('rounded-lg border px-2 py-2 text-left transition-colors', role === r ? 'border-cyan/60 bg-cyan/10' : 'border-ink-500 bg-ink-800 hover:border-ink-500 hover:bg-ink-700')}
                >
                  <span className={cx('block text-xs font-semibold tracking-wide', role === r ? 'text-cyan' : 'text-fg-soft')}>{r}</span>
                  <span className="mt-0.5 block text-[11px] leading-tight text-fg-mute">{ROLE_HELP[r]}</span>
                </button>
              ))}
            </div>
            <Button
              variant="secondary"
              className="w-full"
              onClick={() => {
                startDemoSession(role);
                navigate('/', { replace: true });
              }}
            >
              Continue as {role.toLowerCase()}
            </Button>
          </div>
        </div>
      </div>
    </div>
  );
}
