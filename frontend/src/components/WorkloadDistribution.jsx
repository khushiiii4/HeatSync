import { useMemo } from 'react';
import { Bar, BarChart, CartesianGrid, Legend, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { useTopology, useWorkloadSummary, useWorkloads } from '../api/sources';
import { Panel } from './Panel';
import { AXIS, ChartFrame, ChartTooltip, GRID, PRIORITY_COLOR } from './charts';
import { Skeleton } from './ui';

/** CPU units per rack stacked by priority, from the running workloads. Racks with no work still get a slot. */
export function buildRackStacks(workloads, rackIds) {
  const rows = Object.fromEntries(rackIds.map((id) => [id, { rack: id, LOW: 0, MEDIUM: 0, HIGH: 0 }]));
  for (const w of workloads) {
    if (!w.rackId) continue;
    rows[w.rackId] ||= { rack: w.rackId, LOW: 0, MEDIUM: 0, HIGH: 0 };
    rows[w.rackId][w.priority] += w.cpuDemand;
  }
  return Object.values(rows).sort((a, b) => a.rack.localeCompare(b.rack));
}

export function CpuByRackChart({ data, height = 280 }) {
  return (
    <ChartFrame height={height} label="CPU units per rack, stacked by workload priority">
      <ResponsiveContainer width="100%" height="100%">
        <BarChart data={data} margin={{ top: 8, right: 8, left: -8, bottom: 0 }} barCategoryGap="18%">
          <CartesianGrid {...GRID} />
          <XAxis dataKey="rack" {...AXIS} interval={0} tick={{ fontSize: 10, fill: '#5d6b88' }} />
          <YAxis {...AXIS} domain={[0, 100]} unit="" label={{ value: 'CPU units', angle: -90, position: 'insideLeft', fill: '#5d6b88', fontSize: 12, dx: 12 }} />
          <Tooltip cursor={{ fill: 'rgb(255 255 255 / 0.04)' }} content={<ChartTooltip digits={0} unit=" units" />} />
          <Legend wrapperStyle={{ fontSize: 12, color: '#b4c0d8', paddingTop: 6 }} iconType="circle" />
          <ReferenceLine y={85} stroke="#fb923c" strokeDasharray="5 4" label={{ value: '85% placement cap', fill: '#fb923c', fontSize: 11, position: 'insideTopRight' }} />
          {['LOW', 'MEDIUM', 'HIGH'].map((p, i, a) => (
            <Bar key={p} dataKey={p} name={`${p.charAt(0)}${p.slice(1).toLowerCase()} priority`} stackId="cpu" fill={PRIORITY_COLOR[p]} radius={i === a.length - 1 ? [4, 4, 0, 0] : 0} isAnimationActive={false} />
          ))}
        </BarChart>
      </ResponsiveContainer>
    </ChartFrame>
  );
}

export function WorkloadDistribution() {
  const summary = useWorkloadSummary();
  const running = useWorkloads({ status: 'RUNNING' });
  const topo = useTopology();

  const rackIds = useMemo(() => (topo.data || []).flatMap((z) => z.racks.map((r) => r.id)), [topo.data]);
  const data = useMemo(() => buildRackStacks(running.data || [], rackIds), [running.data, rackIds]);

  return (
    <Panel
      title="Workload distribution"
      subtitle={summary.hasData ? `${summary.data.runningCount} running · ${summary.data.totalCpuUnits} CPU units · ${summary.data.pendingCount} pending` : undefined}
      sources={[summary, running]}
      skeleton={<Skeleton className="h-[280px]" />}
    >
      <CpuByRackChart data={data} />
    </Panel>
  );
}
