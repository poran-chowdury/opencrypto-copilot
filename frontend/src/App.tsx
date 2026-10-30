import { Suspense, lazy, useState } from 'react';
import { Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { ErrorBoundary } from '@/components/ErrorBoundary';
import { AlertTriangle } from 'lucide-react';
import { SidebarProvider } from '@/components/ui/sidebar';
import { Sheet, SheetContent, SheetDescription, SheetTitle } from '@/components/ui/sheet';
import { AppSidebar } from '@/components/app/AppSidebar';
import { Topbar } from '@/components/app/Topbar';
import { Ticker } from '@/components/app/Ticker';
import { CommandMenu } from '@/components/app/CommandMenu';
import { Copilot } from '@/components/Copilot';
import { useMarketStatus } from '@/lib/queries';
import { useMediaQuery, useStoredState } from '@/hooks/use-common';
import { Loading } from '@/components/common';

// Pages load on demand (code splitting): the first paint only downloads the shell and the current page.
const page = <K extends string>(load: () => Promise<Record<K, React.ComponentType>>, name: K) =>
  lazy(() => load().then(m => ({ default: m[name] })));
const Markets = page(() => import('@/pages/Markets'), 'Markets');
const AssetDetail = page(() => import('@/pages/AssetDetail'), 'AssetDetail');
const PortfolioPage = page(() => import('@/pages/Portfolio'), 'PortfolioPage');
const Trade = page(() => import('@/pages/Trade'), 'Trade');
const ActivityPage = page(() => import('@/pages/Activity'), 'ActivityPage');
const Wallet = page(() => import('@/pages/Wallet'), 'Wallet');
const Status = page(() => import('@/pages/Status'), 'Status');
const MarketLab = page(() => import('@/pages/MarketLab'), 'MarketLab');
const Knowledge = page(() => import('@/pages/Knowledge'), 'Knowledge');
const Assistant = page(() => import('@/pages/Assistant'), 'Assistant');

export function App() {
  const [searchOpen, setSearchOpen] = useState(false);
  const [copilot, setCopilot] = useStoredState<'open' | 'closed'>('oc.copilot', 'closed');
  const docked = useMediaQuery('(min-width: 1280px)');
  const copilotOpen = copilot === 'open';
  const toggleCopilot = () => setCopilot(copilotOpen ? 'closed' : 'open');
  const status = useMarketStatus();
  const { pathname } = useLocation();

  return (
    <SidebarProvider>
      <a href="#main" className="sr-only z-50 rounded-md bg-primary px-3 py-2 text-primary-foreground focus:not-sr-only focus:fixed focus:top-2 focus:left-2">Skip to content</a>
      <aside aria-label="Sidebar" className="contents"><AppSidebar /></aside>
      <div className="relative flex min-h-svh w-full min-w-0 flex-1 flex-col bg-background">
        <Topbar onSearch={() => setSearchOpen(true)} copilotOpen={copilotOpen} onToggleCopilot={toggleCopilot} />
        <Ticker />
        {status.isError && (
          <div role="alert" className="flex items-center gap-2 border-b border-down/30 bg-down/10 px-4 py-2 text-sm">
            <AlertTriangle className="size-4 text-down" aria-hidden="true" />
            Can't reach the API gateway. Start the backend with <code>scripts/run-local.sh</code> or <code>docker compose up</code>.
          </div>
        )}
        <div className="flex min-h-0 flex-1">
          <main id="main" tabIndex={-1} className="min-w-0 flex-1 px-4 py-5 outline-none md:px-6 md:py-6">
            <div className="mx-auto w-full max-w-[1600px]">
              <ErrorBoundary resetKey={pathname}>
              <Suspense fallback={<Loading rows={8} />}>
              <Routes>
                <Route path="/" element={<Markets />} />
                <Route path="/assets/:symbol" element={<AssetDetail />} />
                <Route path="/portfolio" element={<PortfolioPage />} />
                <Route path="/trade" element={<Trade />} />
                <Route path="/activity" element={<ActivityPage />} />
                <Route path="/wallet" element={<Wallet />} />
                <Route path="/status" element={<Status />} />
                <Route path="/lab" element={<MarketLab />} />
                <Route path="/knowledge" element={<Knowledge />} />
                <Route path="/assistant" element={<Assistant />} />
                <Route path="*" element={<Navigate to="/" replace />} />
              </Routes>
              </Suspense>
              </ErrorBoundary>
            </div>
          </main>
          {copilotOpen && docked && (
            <aside aria-label="AI copilot" className="sticky top-14 hidden h-[calc(100svh-3.5rem)] w-[380px] shrink-0 border-l bg-card/50 xl:flex">
              <ErrorBoundary><Copilot onClose={toggleCopilot} /></ErrorBoundary>
            </aside>
          )}
        </div>
      </div>
      {!docked && (
        <Sheet open={copilotOpen} onOpenChange={o => setCopilot(o ? 'open' : 'closed')}>
          <SheetContent side="right" className="w-full p-0 sm:max-w-md" showCloseButton={false}>
            <SheetTitle className="sr-only">AI copilot</SheetTitle>
            <SheetDescription className="sr-only">Ask questions about your simulated portfolio</SheetDescription>
            <Copilot onClose={toggleCopilot} />
          </SheetContent>
        </Sheet>
      )}
      <CommandMenu open={searchOpen} onOpenChange={setSearchOpen} onToggleCopilot={toggleCopilot} />
    </SidebarProvider>
  );
}
