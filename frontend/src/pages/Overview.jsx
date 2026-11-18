import { useState } from 'react';
import { AlertList } from '../components/AlertList';
import { CoolingZoneCards } from '../components/CoolingZoneCards';
import { DecisionFeed } from '../components/DecisionFeed';
import { KpiStrip } from '../components/KpiStrip';
import { PlacementExplainer } from '../components/PlacementExplainer';
import { RackHeatMap } from '../components/RackHeatMap';
import { WorkloadDistribution } from '../components/WorkloadDistribution';
import { useWorkloads } from '../api/sources';

/** Plan section 9.2: KPI strip, heat map as the centrepiece, alerts and the decision feed beside it. */
export default function Overview() {
  const [explain, setExplain] = useState(null);
  const running = useWorkloads({ status: 'RUNNING' });
  const name = explain != null ? (running.data || []).find((w) => w.id === explain)?.name : undefined;

  return (
    <div className="space-y-5">
      <KpiStrip />
      <div className="grid items-start gap-5 xl:grid-cols-[minmax(0,1.55fr)_minmax(0,1fr)]">
        <div className="space-y-5">
          <RackHeatMap />
          <CoolingZoneCards />
          <WorkloadDistribution />
        </div>
        <div className="space-y-5">
          <AlertList />
          <DecisionFeed onExplain={setExplain} />
        </div>
      </div>
      <PlacementExplainer workloadId={explain} workloadName={name} onClose={() => setExplain(null)} />
    </div>
  );
}
