import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react';

/**
 * Light/dark/system theme as a class on <html>. A tiny replacement for next-themes, which injects a
 * server-rendering <script> that React warns about in a client-only Vite app. index.html applies the
 * stored theme before React loads, so there is no flash of the wrong theme.
 */
export type Theme = 'dark' | 'light' | 'system';
interface ThemeState { theme: Theme; resolvedTheme: 'dark' | 'light'; setTheme: (t: Theme) => void }

const KEY = 'oc.theme';
const ThemeContext = createContext<ThemeState>({ theme: 'dark', resolvedTheme: 'dark', setTheme: () => {} });
const systemDark = () => window.matchMedia('(prefers-color-scheme: dark)').matches;
const stored = (): Theme => { try { const v = localStorage.getItem(KEY); return v === 'light' || v === 'system' ? v : 'dark'; } catch { return 'dark'; } };

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [theme, setThemeState] = useState<Theme>(stored);
  const [system, setSystem] = useState(systemDark);
  const resolvedTheme = theme === 'system' ? (system ? 'dark' : 'light') : theme;

  useEffect(() => {
    const mq = window.matchMedia('(prefers-color-scheme: dark)');
    const on = () => setSystem(mq.matches);
    mq.addEventListener('change', on);
    return () => mq.removeEventListener('change', on);
  }, []);
  useEffect(() => {
    const root = document.documentElement;
    root.classList.toggle('dark', resolvedTheme === 'dark');
    root.style.colorScheme = resolvedTheme;
  }, [resolvedTheme]);

  const setTheme = useCallback((t: Theme) => {
    setThemeState(t);
    try { localStorage.setItem(KEY, t); } catch { /* storage unavailable */ }
  }, []);

  return <ThemeContext.Provider value={{ theme, resolvedTheme, setTheme }}>{children}</ThemeContext.Provider>;
}

export const useTheme = () => useContext(ThemeContext);
