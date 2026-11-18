// Heat map colour scale (plan section 9.3). Fill shows the current temperature:
// below 26 blue, 26 to 30 green, 30 to 34 amber (WATCH), 34 to 38 orange (WARNING), 38 and above dark red (CRITICAL).
export const SCALE = [
  { id: 'cool', min: -Infinity, max: 26, label: 'Below 26 °C', fill: '#1d4ed8', soft: '#60a5fa' },
  { id: 'normal', min: 26, max: 30, label: '26 – 30 °C', fill: '#0f766e', soft: '#2dd4bf' },
  { id: 'watch', min: 30, max: 34, label: '30 – 34 °C  WATCH', fill: '#a16207', soft: '#fbbf24' },
  { id: 'warning', min: 34, max: 38, label: '34 – 38 °C  WARNING', fill: '#c2410c', soft: '#fb923c' },
  { id: 'critical', min: 38, max: Infinity, label: '38 °C +  CRITICAL', fill: '#991b1b', soft: '#f87171' },
];

export const STALE_FILL = '#3a4560';

/** Band for a temperature in °C. A boundary belongs to the hotter band (26 is green, 30 is amber, ...). */
export function bandFor(tempC) {
  if (tempC == null || tempC === '' || Number.isNaN(Number(tempC))) return null;
  const t = Number(tempC);
  for (const band of SCALE) {
    if (t >= band.min && t < band.max) return band;
  }
  return SCALE[SCALE.length - 1];
}

/** Fill colour of a heat map cell. A stale sensor or a missing value is grey. */
export function fillFor(tempC, { stale = false } = {}) {
  if (stale) return STALE_FILL;
  const band = bandFor(tempC);
  return band ? band.fill : STALE_FILL;
}

/** Outline shows the forecast risk (telemetry riskLevel, computed on max(current, 60 s forecast)). */
export const RISK_OUTLINE = {
  NORMAL: null,
  WATCH: '#fbbf24',
  WARNING: '#fb923c',
  CRITICAL: '#f87171',
};

export function outlineFor(riskLevel) {
  return Object.prototype.hasOwnProperty.call(RISK_OUTLINE, riskLevel) ? RISK_OUTLINE[riskLevel] : null;
}

/** Rising, falling or steady, with a dead band so sensor noise does not flicker the arrow. */
export function trendDirection(trendCPerMin, deadBand = 0.3) {
  const t = Number(trendCPerMin);
  if (!Number.isFinite(t) || Math.abs(t) < deadBand) return 'flat';
  return t > 0 ? 'up' : 'down';
}

/** Severity ranking used to sort alerts and compare risk levels. */
export const RISK_RANK = { NORMAL: 0, WATCH: 1, WARNING: 2, CRITICAL: 3 };
export const compareRisk = (a, b) => (RISK_RANK[b] ?? 0) - (RISK_RANK[a] ?? 0);
