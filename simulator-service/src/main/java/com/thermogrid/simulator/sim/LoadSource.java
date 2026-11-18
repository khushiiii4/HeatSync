package com.thermogrid.simulator.sim;

import java.util.Map;

/**
 * Where the CPU units of each rack come from. {@code rackLoads()} returns the total demand per rack id of the
 * workloads currently running, or null while no workload data has been loaded yet; in that case the engine keeps the
 * rack's previous (static, configured) load so the simulation is not silently emptied.
 */
@FunctionalInterface
public interface LoadSource {

    /** No workload data: every rack keeps its configured static load. */
    LoadSource STATIC = () -> null;

    Map<String, Double> rackLoads();
}
