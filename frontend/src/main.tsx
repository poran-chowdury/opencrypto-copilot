import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ThemeProvider } from '@/lib/theme';
import { TooltipProvider } from '@/components/ui/tooltip';
import { Toaster } from '@/components/ui/sonner';
import { App } from './App';
import { ApiError } from '@/lib/api';
import { IdentityProvider } from '@/lib/identity';
import './index.css';

// Server state lives in React Query: cached per key, refetched in the background, deduplicated.
const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 5_000,
      refetchOnWindowFocus: true,
      // Retry network blips, but never retry client errors (4xx) or rate limiting.
      retry: (count, error) => !(error instanceof ApiError && error.status >= 400 && error.status < 500) && count < 2,
    },
  },
});

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    {/* Dark-first like a trading terminal; the user can switch to light or follow the OS. */}
    <ThemeProvider>
      <QueryClientProvider client={queryClient}>
        <IdentityProvider>
          <BrowserRouter>
            <TooltipProvider delayDuration={300}>
              <App />
              <Toaster position="bottom-right" richColors closeButton />
            </TooltipProvider>
          </BrowserRouter>
        </IdentityProvider>
      </QueryClientProvider>
    </ThemeProvider>
  </StrictMode>,
);
