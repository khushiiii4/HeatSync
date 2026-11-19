package com.thermogrid.gateway;

import org.springframework.http.HttpMethod;

/**
 * Role rules (plan section 8.1):
 * <ul>
 *   <li>VIEWER: every read (GET, HEAD).</li>
 *   <li>OPERATOR: VIEWER plus every operational write (workloads, scheduler, cooling mode, failure injection).</li>
 *   <li>ADMIN: OPERATOR plus simulator control, the flood generator, synthetic-data cleanup and demo seeding.</li>
 * </ul>
 * Anything that is not a plain read needs at least OPERATOR, so a new write endpoint is never open by accident.
 */
public final class RolePolicy {

    private RolePolicy() {
    }

    /** The public routes: only the login call is reachable without a token. */
    public static boolean isPublic(HttpMethod method, String path) {
        return HttpMethod.POST.equals(method) && "/api/auth/login".equals(stripTrailingSlash(path));
    }

    public static Role minimumRole(HttpMethod method, String path) {
        String p = stripTrailingSlash(path);
        if (HttpMethod.GET.equals(method) || HttpMethod.HEAD.equals(method)) return Role.VIEWER;
        if (p.equals("/api/sim/control") || p.equals("/api/sim/flood") || p.equals("/api/workloads/seed-demo")
                || p.equals("/api/telemetry/synthetic")) {
            return Role.ADMIN;
        }
        return Role.OPERATOR;
    }

    private static String stripTrailingSlash(String p) {
        return p.length() > 1 && p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
    }
}
