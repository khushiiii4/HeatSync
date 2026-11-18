import '@testing-library/jest-dom/vitest';
import { act, cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '../api/client';
import App from '../App';
import { hasRole } from '../lib/roles';
import { AuthProvider, STORAGE_KEY, useAuth } from './AuthContext';

// Replaces the network: every request is answered by `respond(config)` and recorded in `seen`.
let seen;
let respond;
const reply = (config, status, data = {}) => {
  const res = { data, status, statusText: String(status), headers: {}, config };
  return status >= 200 && status < 300 ? Promise.resolve(res) : Promise.reject(Object.assign(new Error(`HTTP ${status}`), { config, response: res, isAxiosError: true }));
};

const LOGIN_OK = { token: 'jwt-abc', tokenType: 'Bearer', expiresInSeconds: 28800, user: { username: 'operator', role: 'OPERATOR' } };

function Probe() {
  const { user, role, signIn, logout } = useAuth();
  return (
    <div>
      <p data-testid="who">{user ? `${user.username}:${role}` : 'anonymous'}</p>
      <button onClick={() => signIn('operator', 'pw')}>sign-in</button>
      <button onClick={() => signIn('operator', 'bad').catch(() => {})}>sign-in-bad</button>
      <button onClick={logout}>sign-out</button>
      <button onClick={() => api.get('/api/topology').catch(() => {})}>call</button>
    </div>
  );
}

const setup = (props = {}) => render(<AuthProvider {...props}><Probe /></AuthProvider>);

beforeEach(() => {
  localStorage.clear();
  seen = [];
  respond = (config) => reply(config, 200, {});
  api.defaults.adapter = (config) => {
    seen.push(config);
    return respond(config);
  };
});
afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

describe('login and logout', () => {
  it('signs in, keeps the JWT in memory and localStorage, and shows who is signed in', async () => {
    respond = (config) => (config.url === '/api/auth/login' ? reply(config, 200, LOGIN_OK) : reply(config, 200, {}));
    setup();
    await userEvent.click(screen.getByText('sign-in'));
    await waitFor(() => expect(screen.getByTestId('who')).toHaveTextContent('operator:OPERATOR'));
    expect(JSON.parse(localStorage.getItem(STORAGE_KEY)).token).toBe('jwt-abc');
    expect(JSON.parse(seen[0].data)).toEqual({ username: 'operator', password: 'pw' });
  });

  it('attaches the Authorization header to later requests, and not before sign-in', async () => {
    respond = (config) => (config.url === '/api/auth/login' ? reply(config, 200, LOGIN_OK) : reply(config, 200, {}));
    setup();
    await userEvent.click(screen.getByText('call'));
    expect(seen.at(-1).headers.Authorization).toBeUndefined();
    await userEvent.click(screen.getByText('sign-in'));
    await waitFor(() => expect(screen.getByTestId('who')).toHaveTextContent('OPERATOR'));
    await userEvent.click(screen.getByText('call'));
    expect(seen.at(-1).headers.Authorization).toBe('Bearer jwt-abc');
  });

  it('logout clears the session and the stored token', async () => {
    respond = (config) => (config.url === '/api/auth/login' ? reply(config, 200, LOGIN_OK) : reply(config, 200, {}));
    setup();
    await userEvent.click(screen.getByText('sign-in'));
    await waitFor(() => expect(screen.getByTestId('who')).toHaveTextContent('OPERATOR'));
    await userEvent.click(screen.getByText('sign-out'));
    expect(screen.getByTestId('who')).toHaveTextContent('anonymous');
    expect(localStorage.getItem(STORAGE_KEY)).toBeNull();
    await userEvent.click(screen.getByText('call'));
    expect(seen.at(-1).headers.Authorization).toBeUndefined();
  });

  it('a failed login does not start a session or count as an expired one', async () => {
    const onSessionEnded = vi.fn();
    respond = (config) => reply(config, 401, { message: 'Invalid username or password' });
    setup({ onSessionEnded });
    await userEvent.click(screen.getByText('sign-in-bad'));
    expect(screen.getByTestId('who')).toHaveTextContent('anonymous');
    expect(onSessionEnded).not.toHaveBeenCalled();
  });

  it('restores a stored session on reload and discards an expired one', () => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ token: 't', user: { username: 'admin', role: 'ADMIN' }, expiresAt: Date.now() + 60000 }));
    setup();
    expect(screen.getByTestId('who')).toHaveTextContent('admin:ADMIN');
    cleanup();
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ token: 't', user: { username: 'admin', role: 'ADMIN' }, expiresAt: Date.now() - 1 }));
    setup();
    expect(screen.getByTestId('who')).toHaveTextContent('anonymous');
    expect(localStorage.getItem(STORAGE_KEY)).toBeNull();
  });

  it('signs itself out when the token expires', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const onSessionEnded = vi.fn();
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ token: 't', user: { username: 'v', role: 'VIEWER' }, expiresAt: Date.now() + 5000 }));
    setup({ onSessionEnded });
    await act(async () => { vi.advanceTimersByTime(6000); });
    expect(screen.getByTestId('who')).toHaveTextContent('anonymous');
    expect(onSessionEnded).toHaveBeenCalledWith('expired');
  });
});

describe('401 and 403 handling', () => {
  const signedIn = () => localStorage.setItem(STORAGE_KEY, JSON.stringify({ token: 't', user: { username: 'v', role: 'VIEWER' }, expiresAt: Date.now() + 60000 }));

  it('a 401 clears the session and reports it', async () => {
    signedIn();
    const onSessionEnded = vi.fn();
    respond = (config) => reply(config, 401, {});
    setup({ onSessionEnded });
    await userEvent.click(screen.getByText('call'));
    await waitFor(() => expect(screen.getByTestId('who')).toHaveTextContent('anonymous'));
    expect(localStorage.getItem(STORAGE_KEY)).toBeNull();
    expect(onSessionEnded).toHaveBeenCalledTimes(1);
  });

  it('a 403 calls the forbidden handler and keeps the session', async () => {
    signedIn();
    const onForbidden = vi.fn();
    respond = (config) => reply(config, 403, {});
    setup({ onForbidden });
    await userEvent.click(screen.getByText('call'));
    await waitFor(() => expect(onForbidden).toHaveBeenCalledTimes(1));
    expect(screen.getByTestId('who')).toHaveTextContent('v:VIEWER');
  });
});

describe('role helper and routing', () => {
  it('orders VIEWER < OPERATOR < ADMIN', () => {
    expect(hasRole('VIEWER', 'OPERATOR')).toBe(false);
    expect(hasRole('OPERATOR', 'OPERATOR')).toBe(true);
    expect(hasRole('OPERATOR', 'ADMIN')).toBe(false);
    expect(hasRole('ADMIN', 'OPERATOR')).toBe(true);
    expect(hasRole(null, 'VIEWER')).toBe(false);
  });

  it('an anonymous visitor is sent to the login page, which has no demo-session shortcut', async () => {
    render(<MemoryRouter initialEntries={['/control']}><App /></MemoryRouter>);
    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument();
    expect(screen.queryByText(/demo session/i)).not.toBeInTheDocument();
  });

  it('shows a clear message for wrong credentials', async () => {
    respond = (config) => reply(config, 401, { message: 'Invalid username or password' });
    render(<MemoryRouter initialEntries={['/login']}><App /></MemoryRouter>);
    await userEvent.type(await screen.findByLabelText('Username'), 'viewer');
    await userEvent.type(screen.getByLabelText('Password'), 'wrong');
    await userEvent.click(screen.getByRole('button', { name: /sign in/i }));
    expect(await screen.findByRole('alert')).toHaveTextContent('not accepted');
  });
});
