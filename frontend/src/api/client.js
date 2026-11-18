import axios from 'axios';

// Every call goes to the API gateway and nowhere else (plan section 9). In development and behind nginx the base is
// relative (/api is proxied to the gateway); VITE_API_BASE can point at a gateway URL directly.
const BASE = (import.meta.env?.VITE_API_BASE || '').replace(/\/$/, '');

export const api = axios.create({ baseURL: BASE, timeout: 8000, headers: { Accept: 'application/json' } });

// Auth plumbing: the AuthProvider registers these so the interceptors stay free of React.
let tokenGetter = () => null;
let handlers = { onUnauthorized: () => {}, onForbidden: () => {} };
export const configureAuth = ({ getToken, onUnauthorized, onForbidden }) => {
  if (getToken) tokenGetter = getToken;
  handlers = { ...handlers, ...(onUnauthorized && { onUnauthorized }), ...(onForbidden && { onForbidden }) };
};

api.interceptors.request.use((config) => {
  const token = tokenGetter();
  if (token) config.headers.Authorization = `Bearer ${token}`;
  return config;
});

api.interceptors.response.use(
  (res) => res,
  (err) => {
    const status = err.response?.status;
    if (status === 401 && !String(err.config?.url).includes('/api/auth/login')) handlers.onUnauthorized(err);
    if (status === 403) handlers.onForbidden(err);
    return Promise.reject(err);
  },
);

// The server's own clock, taken from the Date response header, per endpoint. Used only when a payload has no
// timestamp of its own, so a wrong laptop clock can never make data look fresh or stale. (Browsers expose the
// header only for same-origin or proxied requests, which is how the dashboard is served.)
export const serverDates = new Map();

export async function get(path, params) {
  const res = await api.get(path, params ? { params } : undefined);
  const date = res.headers?.date;
  if (date) {
    const iso = new Date(date).toISOString();
    serverDates.set(path, iso);
  }
  return res.data;
}

/** Human message for a failed request, never including secrets. */
export function errorMessage(err) {
  const d = err?.response?.data;
  if (d?.message) return d.message;
  if (err?.response?.status === 503 && d?.service) return `${d.service} is unavailable`;
  if (err?.response) return `Request failed (${err.response.status})`;
  if (err?.code === 'ECONNABORTED') return 'The request timed out';
  return 'The gateway could not be reached';
}

/** Which service a failed request blames: the gateway fallback names it, otherwise null. */
export const failedService = (err) => err?.response?.data?.service || null;
