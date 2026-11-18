/** ThermoGrid mark: a 2x2 rack grid with the diagonal cells lit, echoing the heat map. */
export function BrandMark({ size = 32 }) {
  return (
    <svg width={size} height={size} viewBox="0 0 32 32" role="img" aria-label="ThermoGrid">
      <defs>
        <linearGradient id="tg-brand" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor="#22d3ee" />
          <stop offset="1" stopColor="#3b82f6" />
        </linearGradient>
      </defs>
      <rect width="32" height="32" rx="9" fill="#0b1426" stroke="#1f2d4a" />
      <g fill="url(#tg-brand)">
        <rect x="6.5" y="6.5" width="8.5" height="8.5" rx="2.2" />
        <rect x="17" y="6.5" width="8.5" height="8.5" rx="2.2" opacity=".42" />
        <rect x="6.5" y="17" width="8.5" height="8.5" rx="2.2" opacity=".42" />
        <rect x="17" y="17" width="8.5" height="8.5" rx="2.2" />
      </g>
    </svg>
  );
}

export function Wordmark() {
  return (
    <div className="flex items-center gap-3">
      <BrandMark />
      <div className="leading-tight">
        <p className="text-[17px] font-semibold tracking-tight text-fg">ThermoGrid</p>
        <p className="text-[11px] font-medium uppercase tracking-[0.14em] text-fg-mute">Thermal operations</p>
      </div>
    </div>
  );
}
