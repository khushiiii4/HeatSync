import { api, get } from './client';

// One function per backend endpoint used by the dashboard (plan section 8.2). Paths are exactly the gateway paths.
export const fetchServices = () => get('/api/platform/services');
export const fetchTopology = () => get('/api/topology');
export const fetchRacksLatest = () => get('/api/telemetry/racks/latest');
export const fetchZonesLatest = () => get('/api/telemetry/zones/latest');
export const fetchRackHistory = (rackId, minutes = 10) => get(`/api/telemetry/racks/${rackId}/history`, { minutes });
export const fetchAlerts = (state = 'ACTIVE', limit = 50) => get('/api/alerts', { state, limit });
export const fetchMigrations = (limit = 20) => get('/api/scheduler/migrations', { limit });
export const fetchDecisions = (limit = 20) => get('/api/scheduler/decisions', { limit });
export const fetchSchedulerStatus = () => get('/api/scheduler/status');
export const fetchCoolingUnits = () => get('/api/cooling/units');
export const fetchCoolingActions = (limit = 20) => get('/api/cooling/actions', { limit });
export const fetchCoolingSummary = () => get('/api/cooling/summary');
export const fetchWorkloadSummary = () => get('/api/workloads/summary');
export const fetchWorkloads = ({ status, rackId } = {}) => get('/api/workloads', { ...(status && { status }), ...(rackId && { rackId }) });
export const fetchSimState = () => get('/api/sim/state');
export const fetchFailures = (active = true) => get('/api/sim/failures', { active });

// Mutations
export const login = (username, password) => api.post('/api/auth/login', { username, password }).then((r) => r.data);
export const submitWorkload = (body) => api.post('/api/workloads', body).then((r) => r.data);
export const removeWorkload = (id) => api.delete(`/api/workloads/${id}`).then((r) => r.data);
export const seedDemo = () => api.post('/api/workloads/seed-demo').then((r) => r.data);
export const injectFailure = (body) => api.post('/api/sim/failures', body).then((r) => r.data);
export const clearFailure = (id) => api.delete(`/api/sim/failures/${id}`).then((r) => r.data);
export const simControl = (action) => api.post('/api/sim/control', { action }).then((r) => r.data);
export const setAutopilot = (enabled) => api.post('/api/scheduler/autopilot', { enabled }).then((r) => r.data);
export const rebalanceNow = () => api.post('/api/scheduler/rebalance').then((r) => r.data);
export const setCoolingMode = (zoneId, mode, outputPct) =>
  api.post(`/api/cooling/units/${zoneId}/mode`, { mode, ...(outputPct != null && { outputPct }) }).then((r) => r.data);
