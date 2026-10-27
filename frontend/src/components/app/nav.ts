import { Activity, ArrowLeftRight, BookOpen, ChartCandlestick, FlaskConical, Gauge, PieChart, Wallet, type LucideIcon } from 'lucide-react';

export interface NavItem { to: string; label: string; icon: LucideIcon; keywords?: string }

export const NAV_GROUPS: { label: string; items: NavItem[] }[] = [
  {
    label: 'Trading',
    items: [
      { to: '/', label: 'Markets', icon: ChartCandlestick, keywords: 'prices coins assets' },
      { to: '/trade', label: 'Trade', icon: ArrowLeftRight, keywords: 'buy sell swap convert' },
      { to: '/portfolio', label: 'Portfolio', icon: PieChart, keywords: 'holdings profit loss pnl' },
      { to: '/activity', label: 'Activity', icon: Activity, keywords: 'history transactions' },
      { to: '/wallet', label: 'Wallet', icon: Wallet, keywords: 'deposit ledger balances audit' },
    ],
  },
  {
    label: 'Platform',
    items: [
      { to: '/status', label: 'System status', icon: Gauge, keywords: 'health services observability' },
      { to: '/lab', label: 'Market Lab', icon: FlaskConical, keywords: 'simulation shock events dataset' },
      { to: '/knowledge', label: 'Knowledge', icon: BookOpen, keywords: 'rag search docs' },
    ],
  },
];
