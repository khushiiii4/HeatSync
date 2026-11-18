import { useState } from 'react';
import { Navigate, useNavigate } from 'react-router-dom';
import { LogIn } from 'lucide-react';
import { useAuth } from '../auth/AuthContext';
import { errorMessage } from '../api/client';
import { Wordmark } from '../components/Brand';
import { Button, cx, inputCls } from '../components/ui';

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
  const { user, signIn } = useAuth();
  const navigate = useNavigate();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState(null); // { kind: 'credentials' | 'unavailable', text }

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
      else setProblem({ kind: 'unavailable', text: `${errorMessage(err)}. Check that the gateway and auth-service are running.` });
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
        </div>
      </div>
    </div>
  );
}
