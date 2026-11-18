// Roles (plan section 8.1): VIEWER reads, OPERATOR adds operational actions, ADMIN adds simulator control and seeding.
// The gateway is the real enforcement point; the UI only hides or disables what the user may not do.
const RANK = { VIEWER: 1, OPERATOR: 2, ADMIN: 3 };
export const ROLES = ['VIEWER', 'OPERATOR', 'ADMIN'];
export const hasRole = (role, minimum) => (RANK[role] ?? 0) >= (RANK[minimum] ?? 99);
