import { useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTheme } from '@/lib/theme';
import { Bot, Moon, Sun } from 'lucide-react';
import { Command, CommandDialog, CommandEmpty, CommandGroup, CommandInput, CommandItem, CommandList, CommandSeparator, CommandShortcut } from '@/components/ui/command';
import { useCatalogue } from '@/lib/queries';
import { usd } from '@/lib/format';
import { CoinIcon, PriceChange } from '@/components/market/primitives';
import { NAV_GROUPS } from './nav';

/** ⌘K / Ctrl+K: jump to any page or asset, or run an action, from the keyboard. */
export function CommandMenu({ open, onOpenChange, onToggleCopilot }: { open: boolean; onOpenChange: (o: boolean) => void; onToggleCopilot: () => void }) {
  const navigate = useNavigate();
  const { resolvedTheme, setTheme } = useTheme();
  const assets = useCatalogue().data?.items ?? [];

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key.toLowerCase() === 'k' && (e.metaKey || e.ctrlKey)) { e.preventDefault(); onOpenChange(!open); }
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [open, onOpenChange]);

  const run = (fn: () => void) => { onOpenChange(false); fn(); };

  return (
    <CommandDialog open={open} onOpenChange={onOpenChange} title="Search" description="Jump to a page or an asset, or run an action">
      {/* The current shadcn CommandDialog expects its own <Command> root inside (cmdk context). */}
      <Command>
      <CommandInput placeholder="Search assets, pages, actions…" />
      <CommandList>
        <CommandEmpty>No results.</CommandEmpty>
        <CommandGroup heading="Assets">
          {assets.map(a => (
            <CommandItem key={a.symbol} value={`${a.symbol} ${a.name}`} onSelect={() => run(() => navigate(`/assets/${a.symbol}`))}>
              <CoinIcon symbol={a.symbol} imageUrl={a.imageUrl} size={18} />
              <span className="font-medium">{a.name}</span>
              <span className="text-muted-foreground">{a.symbol}</span>
              <span className="num ml-auto text-xs">{usd(a.priceUsd)}</span>
              <PriceChange value={a.change24hPct} className="w-16 justify-end text-xs" />
            </CommandItem>
          ))}
        </CommandGroup>
        <CommandSeparator />
        {NAV_GROUPS.map(g => (
          <CommandGroup key={g.label} heading={g.label}>
            {g.items.map(item => (
              <CommandItem key={item.to} value={`${item.label} ${item.keywords ?? ''}`} onSelect={() => run(() => navigate(item.to))}>
                <item.icon /> {item.label}
              </CommandItem>
            ))}
          </CommandGroup>
        ))}
        <CommandSeparator />
        <CommandGroup heading="Actions">
          <CommandItem value="buy bitcoin btc" onSelect={() => run(() => navigate('/trade?from=USD&to=BTC'))}>
            <CoinIcon symbol="BTC" imageUrl={assets.find(a => a.symbol === 'BTC')?.imageUrl} size={18} /> Buy Bitcoin
          </CommandItem>
          <CommandItem value="toggle theme dark light" onSelect={() => run(() => setTheme(resolvedTheme === 'dark' ? 'light' : 'dark'))}>
            {resolvedTheme === 'dark' ? <Sun /> : <Moon />} Switch to {resolvedTheme === 'dark' ? 'light' : 'dark'} theme
          </CommandItem>
          <CommandItem value="copilot ai assistant chat" onSelect={() => run(onToggleCopilot)}>
            <Bot /> Toggle copilot <CommandShortcut>AI</CommandShortcut>
          </CommandItem>
        </CommandGroup>
      </CommandList>
      </Command>
    </CommandDialog>
  );
}
