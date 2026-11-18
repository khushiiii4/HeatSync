import { useMemo } from 'react';
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useIsFetching, useQueryClient } from '@tanstack/react-query';
import { Activity, Boxes, Fan, LayoutDashboard, LogOut, RefreshCw, ShieldCheck, SlidersHorizontal } from 'lucide-react';
import { useAuth } from '../auth/AuthContext';
import { useRacks, useSchedulerStatus, useServices } from '../api/sources';
import { classify, overallStatus, ServiceHealthStrip } from './ServiceHealthStrip';
import { useSourceHealth } from '../state/sourceHealth';
import { hasRole } from '../lib/roles';
import { fmtClock } from '../lib/format';
import { Wordmark } from './Brand';
import { Button, Tag, cx } from './ui';
import { useToast } from './Toast';
import { MigrationWatcher } from './MigrationWatcher';

const NAV = [
  { to: '/', label: 'Overview', icon: LayoutDashboard, end: true },
  { to: '/workloads', label: 'Workloads', icon: Boxes },
  { to: '/cooling', label: 'Cooling', icon: Fan },
  { to: '/events', label: 'Event history', icon: Activity },
  { to: '/control', label: 'Control Room', icon: SlidersHorizontal, minRole: 'OPERATOR' },
];

const TITLES = [
  [/^\/racks\//, 'Rack detail'],
  [/^\/workloads/, 'Workloads'],
  [/^\/cooling/, 'Cooling'],
  [/^\/events/, 'Event history'],
  [/^\/control/, 'Control Room'],
  [/^\//, 'Overview'],
];

function Sidebar() {
  const { role } = useAuth();
  return (
    <aside className="hidden w-[248px] shrink-0 flex-col border-r border-ink-600/80 bg-ink-900/70 backdrop-blur lg:flex">
      <div className="px-5 pb-5 pt-6">
        <Wordmark />
      </div>
      <nav className="flex-1 space-y-1 px-3" aria-label="Primary">
        <p className="px-3 pb-1.5 pt-2 text-[11px] font-semibold uppercase tracking-[0.14em] text-fg-faint">Operations</p>
        {NAV.filter((n) => !n.minRole || hasRole(role, n.minRole)).map(({ to, label, icon: Icon, end }) => (
          <NavLink
            key={to}
            to={to}
            end={end}
            className={({ isActive }) =>
              cx(
                'group relative flex items-center gap-3 rounded-lg px-3 py-2.5 text-[15px] font-medium transition-colors',
                isActive ? 'bg-ink-700 text-fg' : 'text-fg-mute hover:bg-ink-800 hover:text-fg',
              )
            }
          >
            {({ isActive }) => (
              <>
                <span className={cx('absolute left-0 top-2 h-[calc(100%-1rem)] w-[3px] rounded-r-full transition-opacity', isActive ? 'bg-cyan opacity-100' : 'opacity-0')} aria-hidden />
                <Icon className={cx('h-[18px] w-[18px]', isActive ? 'text-cyan' : 'text-fg-mute group-hover:text-fg-soft')} aria-hidden />
                {label}
              </>
            )}
          </NavLink>
        ))}
      </nav>
      <div className="m-3 rounded-xl border border-ink-600 bg-ink-800/60 p-3.5 text-xs leading-relaxed text-fg-mute">
        <p className="mb-1 flex items-center gap-1.5 font-semibold uppercase tracking-wide text-fg-soft">
          <ShieldCheck className="h-3.5 w-3.5 text-teal" aria-hidden /> Simulated plant
        </p>
        4 cooling zones · 16 racks · 2 s tick. All readings come from the simulator service.
      </div>
    </aside>
  );
}

function AutopilotBadge() {
  const s = useSchedulerStatus();
  if (!s.hasData) {
    return s.down ? <Tag tone="warn">Autopilot unknown</Tag> : <Tag>Autopilot …</Tag>;
  }
  const hold = s.data.mode === 'HOLD';
  return (
    <div className="flex items-center gap-2">
      <Tag tone={s.data.autopilot ? 'cyan' : 'warn'} title={s.stale ? 'Scheduler unavailable; showing the last known value' : undefined}>
        Autopilot {s.data.autopilot ? 'on' : 'off'}
        {s.stale && ' · stale'}
      </Tag>
      {hold && <Tag tone="orange">HOLD mode</Tag>}
    </div>
  );
}

function TopBar() {
  const location = useLocation();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const fetching = useIsFetching();
  const toast = useToast();
  const { user, role, stub, logout } = useAuth();
  const services = useServices();
  const racks = useRacks();
  const sources = useSourceHealth();

  const title = TITLES.find(([re]) => re.test(location.pathname))[1];
  const items = useMemo(() => (services.hasData ? classify(services.data, sources, services.stale) : []), [services.hasData, services.data, sources, services.stale]);
  const status = overallStatus(items, services.down);
  const tone = { ok: 'text-ok', warn: 'text-warn', crit: 'text-crit' }[status.tone];
  const dot = { ok: 'bg-ok', warn: 'bg-warn', crit: 'bg-crit' }[status.tone];

  return (
    <header className="sticky top-0 z-30 border-b border-ink-600/80 bg-ink-950/80 backdrop-blur-md">
      <div className="flex flex-wrap items-center justify-between gap-x-6 gap-y-2 px-6 py-3.5">
        <div className="flex items-center gap-4">
          <div className="lg:hidden">
            <Wordmark />
          </div>
          <div className="hidden lg:block">
            <h1 className="text-[22px] font-semibold tracking-tight text-fg">{title}</h1>
          </div>
        </div>
        <div className="flex flex-wrap items-center gap-x-5 gap-y-2">
          <AutopilotBadge />
          <div className="flex items-center gap-2 text-sm font-medium" role="status" aria-live="polite">
            <span className={cx('h-2.5 w-2.5 rounded-full', dot, status.tone === 'ok' && 'live-dot')} aria-hidden />
            <span className={tone}>{status.text}</span>
          </div>
          <p className="num hidden text-sm text-fg-mute xl:block" title="Timestamp of the newest telemetry reading, from the server clock">
            Server data as of <span className="text-fg-soft">{fmtClock(racks.lastServerTime)}</span>
          </p>
          <Button
            size="sm"
            variant="ghost"
            onClick={() => {
              qc.invalidateQueries();
              toast.info('Refreshing all panels', undefined, 1500);
            }}
            aria-label="Refresh all panels now"
          >
            <RefreshCw className={cx('h-4 w-4', fetching > 0 && 'animate-spin')} aria-hidden />
            <span className="hidden sm:inline">Refresh</span>
          </Button>
          <div className="flex items-center gap-2 border-l border-ink-600 pl-4">
            <div className="text-right leading-tight">
              <p className="text-sm font-medium text-fg">{user?.username}</p>
              <p className="text-[11px] font-semibold uppercase tracking-wide text-fg-mute">
                {role}
                {stub ? ' · demo' : ''}
              </p>
            </div>
            <Button
              size="sm"
              variant="ghost"
              onClick={() => {
                logout();
                navigate('/login');
              }}
              aria-label="Sign out"
            >
              <LogOut className="h-4 w-4" aria-hidden />
            </Button>
          </div>
        </div>
      </div>
      <div className="border-t border-ink-700/70 px-6 py-2">
        <ServiceHealthStrip />
      </div>
      <nav className="flex gap-1 overflow-x-auto border-t border-ink-700/70 px-4 py-1.5 lg:hidden" aria-label="Primary">
        {NAV.filter((n) => !n.minRole || hasRole(role, n.minRole)).map(({ to, label, icon: Icon, end }) => (
          <NavLink key={to} to={to} end={end} className={({ isActive }) => cx('flex items-center gap-2 whitespace-nowrap rounded-md px-3 py-1.5 text-sm font-medium', isActive ? 'bg-ink-700 text-fg' : 'text-fg-mute')}>
            <Icon className="h-4 w-4" aria-hidden /> {label}
          </NavLink>
        ))}
      </nav>
    </header>
  );
}

export function Shell() {
  return (
    <div className="app-bg flex min-h-full">
      <a href="#main" className="sr-only focus:not-sr-only focus:fixed focus:left-3 focus:top-3 focus:z-[70] focus:rounded-md focus:bg-ink-700 focus:px-3 focus:py-2">
        Skip to content
      </a>
      <MigrationWatcher />
      <Sidebar />
      <div className="flex min-w-0 flex-1 flex-col">
        <TopBar />
        <main id="main" className="mx-auto w-full max-w-[1760px] flex-1 px-6 pb-12 pt-6">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
