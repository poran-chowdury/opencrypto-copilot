import { Component, type ErrorInfo, type ReactNode } from 'react';
import { RotateCw, TriangleAlert } from 'lucide-react';
import { Button } from '@/components/ui/button';

/**
 * Catches render errors so one broken component doesn't blank the whole app.
 * `resetKey` (the route) clears the error when the user navigates away.
 */
export class ErrorBoundary extends Component<{ children: ReactNode; resetKey?: string }, { error: Error | null }> {
  state: { error: Error | null } = { error: null };

  static getDerivedStateFromError(error: Error) { return { error }; }

  componentDidCatch(error: Error, info: ErrorInfo) {
    // In production this is where you'd report to Sentry / OpenTelemetry (browser) with the trace id.
    console.error('UI error', error, info.componentStack);
  }

  componentDidUpdate(prev: { resetKey?: string }) {
    if (this.state.error && prev.resetKey !== this.props.resetKey) this.setState({ error: null });
  }

  render() {
    if (!this.state.error) return this.props.children;
    return (
      <div role="alert" className="mx-auto flex max-w-md flex-col items-center gap-3 rounded-xl border border-down/30 bg-down/5 px-6 py-10 text-center">
        <TriangleAlert className="size-8 text-down" aria-hidden="true" />
        <h2 className="text-lg font-semibold">Something went wrong on this page</h2>
        <p className="text-sm text-muted-foreground">The rest of the app still works. Try again, or pick another page from the menu.</p>
        <code className="max-w-full truncate">{this.state.error.message}</code>
        <Button variant="outline" onClick={() => this.setState({ error: null })}><RotateCw /> Try again</Button>
      </div>
    );
  }
}
