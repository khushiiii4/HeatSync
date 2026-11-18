// Shared Recharts styling so every chart on every page looks like part of one system.
export const AXIS = { stroke: '#5d6b88', fontSize: 12, tickLine: false, axisLine: { stroke: '#1f2d4a' } };
export const GRID = { stroke: '#1f2d4a', strokeDasharray: '3 5', vertical: false };

export const PRIORITY_COLOR = { LOW: '#38bdf8', MEDIUM: '#3b82f6', HIGH: '#a78bfa' };
export const ZONE_COLOR = { Z1: '#22d3ee', Z2: '#3b82f6', Z3: '#a78bfa', Z4: '#2dd4bf' };

export function ChartTooltip({ active, payload, label, labelFormatter, unit = '', digits = 1 }) {
  if (!active || !payload?.length) return null;
  return (
    <div className="rounded-lg border border-ink-500 bg-ink-800/95 px-3 py-2.5 text-sm shadow-xl shadow-black/50 backdrop-blur">
      <p className="mb-1.5 font-medium text-fg">{labelFormatter ? labelFormatter(label) : label}</p>
      <ul className="space-y-1">
        {payload
          .filter((p) => p.value != null)
          .map((p) => (
            <li key={p.dataKey} className="flex items-center justify-between gap-5">
              <span className="flex items-center gap-2 text-fg-soft">
                <span className="h-2.5 w-2.5 rounded-sm" style={{ background: p.color || p.stroke || p.fill }} aria-hidden />
                {p.name}
              </span>
              <span className="num font-semibold text-fg">
                {typeof p.value === 'number' ? p.value.toFixed(digits) : p.value}
                {unit}
              </span>
            </li>
          ))}
      </ul>
    </div>
  );
}

export function ChartFrame({ height = 260, children, label }) {
  return (
    <div style={{ height }} role="img" aria-label={label} className="w-full">
      {children}
    </div>
  );
}
