import { useEffect, useMemo, useRef, useState } from 'react';
import { useTheme } from '@/lib/theme';
import {
  AreaSeries, CandlestickSeries, ColorType, CrosshairMode, LineStyle, createChart, createSeriesMarkers,
  type IChartApi, type SeriesMarker, type Time, type UTCTimestamp,
} from 'lightweight-charts';
import { dateTime } from '@/lib/format';

export interface Point { t: number; v: number }
export interface Marker { t: number; label: string; direction: 'up' | 'down' }

interface Props {
  data: Point[];
  label: string;
  formatValue: (v: number) => string;
  /** CSS colour or var(--token); area mode only. Candles use the up/down tokens. */
  color?: string;
  kind?: 'area' | 'candles';
  markers?: Marker[];
  height?: number;
  emptyText?: string;
}

/** Resolve "var(--x)" (or a bare token name) to a concrete colour: canvas charts can't read CSS variables. */
function cssColor(value: string) {
  const m = value.match(/^var\((--[\w-]+)\)$/);
  const name = m ? m[1] : value.startsWith('--') ? value : null;
  return name ? getComputedStyle(document.documentElement).getPropertyValue(name).trim() || '#888' : value;
}
const alpha = (hex: string, a: number) => {
  const h = hex.replace('#', '');
  if (h.length !== 6) return hex;
  const n = parseInt(h, 16);
  return `rgba(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255}, ${a})`;
};

/** Lightweight Charts needs strictly increasing times (seconds). */
function toSeries(data: Point[]) {
  const out: { time: UTCTimestamp; value: number }[] = [];
  for (const p of [...data].sort((a, b) => a.t - b.t)) {
    const time = Math.floor(p.t / 1000) as UTCTimestamp;
    if (out.length && out[out.length - 1].time >= time) out[out.length - 1].value = p.v;
    else out.push({ time, value: p.v });
  }
  return out;
}

/** Group points into ~60 OHLC candles (the API returns sampled prices, not trades). */
function toCandles(points: { time: UTCTimestamp; value: number }[]) {
  const per = Math.max(2, Math.ceil(points.length / 60));
  const out = [];
  for (let i = 0; i < points.length; i += per) {
    const chunk = points.slice(i, i + per);
    const values = chunk.map(c => c.value);
    out.push({ time: chunk[0].time, open: values[0], high: Math.max(...values), low: Math.min(...values), close: values[values.length - 1] });
  }
  return out;
}

const fmtTime = (t: Time) => new Date((t as number) * 1000).toLocaleString([], { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });

export function PriceChart({ data, label, formatValue, color = 'var(--primary)', kind = 'area', markers = [], height = 320, emptyText }: Props) {
  const ref = useRef<HTMLDivElement>(null);
  const { resolvedTheme } = useTheme();
  const series = useMemo(() => toSeries(data), [data]);
  const [hover, setHover] = useState<{ time: Time; value: number } | null>(null);

  useEffect(() => {
    const el = ref.current;
    if (!el || series.length < 2) return;
    const text = cssColor('--muted-foreground');
    const grid = alpha(cssColor('--border').startsWith('#') ? cssColor('--border') : '#888888', 0.6);
    const chart: IChartApi = createChart(el, {
      autoSize: true,
      layout: { background: { type: ColorType.Solid, color: 'transparent' }, textColor: text, fontFamily: 'Geist Variable, ui-sans-serif, system-ui', fontSize: 11 },
      grid: { vertLines: { visible: false }, horzLines: { color: grid, style: LineStyle.Dotted } },
      rightPriceScale: { borderVisible: false, scaleMargins: { top: 0.12, bottom: 0.08 } },
      timeScale: { borderVisible: false, timeVisible: true, secondsVisible: false, tickMarkFormatter: (t: Time) => new Date((t as number) * 1000).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) },
      crosshair: { mode: CrosshairMode.Magnet, vertLine: { labelVisible: false }, horzLine: { labelBackgroundColor: cssColor('--primary') } },
      localization: { timeFormatter: fmtTime, priceFormatter: formatValue },
      handleScroll: false,
      handleScale: false,
    });

    const priceFormat = { type: 'custom' as const, formatter: formatValue, minMove: 0.000001 };
    const up = cssColor('--up'), down = cssColor('--down');
    let s;
    if (kind === 'candles') {
      s = chart.addSeries(CandlestickSeries, { upColor: up, downColor: down, wickUpColor: up, wickDownColor: down, borderVisible: false, priceFormat });
      s.setData(toCandles(series));
    } else {
      const line = cssColor(color);
      s = chart.addSeries(AreaSeries, { lineColor: line, topColor: alpha(line, 0.28), bottomColor: alpha(line, 0.0), lineWidth: 2, priceFormat, priceLineStyle: LineStyle.Dashed });
      s.setData(series);
    }

    // Event markers snap to the nearest point so they sit on the line.
    const first = series[0].time, last = series[series.length - 1].time;
    const snapped: SeriesMarker<Time>[] = markers
      .map(m => ({ ...m, time: Math.floor(m.t / 1000) as UTCTimestamp }))
      .filter(m => m.time >= first && m.time <= last)
      .map(m => {
        const nearest = series.reduce((b, p) => (Math.abs(p.time - m.time) < Math.abs(b.time - m.time) ? p : b), series[0]);
        return { time: nearest.time, position: m.direction === 'up' ? 'belowBar' as const : 'aboveBar' as const,
          color: m.direction === 'up' ? up : down, shape: 'circle' as const, size: 0.6 };
      })
      .sort((a, b) => (a.time as number) - (b.time as number));
    if (snapped.length) createSeriesMarkers(s, snapped);

    chart.timeScale().fitContent();
    chart.subscribeCrosshairMove(param => {
      const d = param.time ? param.seriesData.get(s) : undefined;
      if (!d) return setHover(null);
      const v = d as { value?: number; close?: number };
      setHover({ time: param.time!, value: v.value ?? v.close ?? 0 });
    });
    return () => chart.remove();
  }, [series, kind, color, markers, formatValue, resolvedTheme]);

  if (series.length < 2) {
    return (
      <div className="flex items-center justify-center rounded-lg border border-dashed text-sm text-muted-foreground" style={{ height }}>
        {emptyText ?? 'Collecting data — the chart fills in as prices update.'}
      </div>
    );
  }

  const lastPoint = series[series.length - 1];
  const values = series.map(p => p.value);
  const shown = hover ?? { time: lastPoint.time, value: lastPoint.value };
  return (
    <figure className="relative" aria-label={label}>
      <div className="pointer-events-none absolute top-1 left-1 z-10 rounded-md bg-card/80 px-2 py-1 text-xs backdrop-blur" aria-hidden="true">
        <span className="num font-semibold">{formatValue(shown.value)}</span>
        <span className="ml-2 text-muted-foreground">{fmtTime(shown.time)}</span>
      </div>
      <div ref={ref} style={{ height }} />
      <figcaption className="sr-only">
        {label}: from {formatValue(values[0])} to {formatValue(values[values.length - 1])}, low {formatValue(Math.min(...values))},
        high {formatValue(Math.max(...values))}, last update {dateTime(new Date(lastPoint.time * 1000).toISOString())}.
      </figcaption>
    </figure>
  );
}
