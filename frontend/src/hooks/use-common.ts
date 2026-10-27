import { useEffect, useState } from 'react';

/** Re-render every `ms` (for countdowns). */
export function useNow(ms = 500) {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), ms);
    return () => clearInterval(id);
  }, [ms]);
  return now;
}

/** Debounce a fast-changing value (search boxes) so we don't fire a request per keystroke. */
export function useDebounced<T>(value: T, ms = 300) {
  const [v, setV] = useState(value);
  useEffect(() => {
    const id = setTimeout(() => setV(value), ms);
    return () => clearTimeout(id);
  }, [value, ms]);
  return v;
}

/** True when the media query matches; updates on resize. */
export function useMediaQuery(query: string) {
  const [matches, setMatches] = useState(() => window.matchMedia(query).matches);
  useEffect(() => {
    const mq = window.matchMedia(query);
    const on = () => setMatches(mq.matches);
    mq.addEventListener('change', on);
    return () => mq.removeEventListener('change', on);
  }, [query]);
  return matches;
}

const read = (key: string, fallback: string) => { try { return localStorage.getItem(key) ?? fallback; } catch { return fallback; } };
const write = (key: string, value: string) => { try { localStorage.setItem(key, value); } catch { /* storage unavailable */ } };

/** A string preference remembered per browser (try/catch: storage can be blocked). */
export function useStoredState<T extends string>(key: string, fallback: T) {
  const [value, setValue] = useState<T>(() => read(key, fallback) as T);
  useEffect(() => write(key, value), [key, value]);
  return [value, setValue] as const;
}
