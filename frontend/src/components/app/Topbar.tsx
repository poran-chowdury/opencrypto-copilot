import { useState, type FormEvent } from 'react';
import { useTheme } from '@/lib/theme';
import { Bot, Check, Monitor, Moon, Search, Sun, UserRound } from 'lucide-react';
import { SidebarTrigger } from '@/components/ui/sidebar';
import { Button } from '@/components/ui/button';
import { Kbd } from '@/components/ui/kbd';
import { Separator } from '@/components/ui/separator';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Avatar, AvatarFallback } from '@/components/ui/avatar';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuGroup, DropdownMenuItem, DropdownMenuLabel, DropdownMenuRadioGroup,
  DropdownMenuRadioItem, DropdownMenuSeparator, DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { useMarketStatus } from '@/lib/queries';
import { isValidUser, useIdentity } from '@/lib/identity';
import { LiveDot } from '@/components/market/primitives';
import type { DemoRole } from '@/lib/api';

export function Topbar({ onSearch, copilotOpen, onToggleCopilot }: { onSearch: () => void; copilotOpen: boolean; onToggleCopilot: () => void }) {
  const status = useMarketStatus().data;
  const live = status?.provider === 'coingecko';
  const down = status?.priceFeed === 'DOWN';

  return (
    <header className="sticky top-0 z-20 flex h-14 items-center gap-2 border-b bg-background/80 px-3 backdrop-blur md:px-4">
      <SidebarTrigger />
      <Separator orientation="vertical" className="mx-1 h-5" />
      <Button variant="outline" onClick={onSearch} className="h-9 min-w-0 shrink justify-start gap-2 text-muted-foreground max-sm:w-9 max-sm:justify-center max-sm:px-0 sm:w-full sm:max-w-sm">
        <Search /> <span className="truncate max-sm:sr-only">Search assets, pages…</span>
        <Kbd className="ml-auto hidden sm:inline-flex">Ctrl K</Kbd>
      </Button>
      <div className="ml-auto flex shrink-0 items-center gap-1 md:gap-2">
        {status && (
          <Tooltip>
            <TooltipTrigger asChild>
              <span tabIndex={0} className="hidden items-center gap-1.5 rounded-full border px-2.5 py-1 text-xs font-medium sm:inline-flex">
                <LiveDot tone={down ? 'down' : live ? 'up' : 'muted'} />
                {down ? 'Feed down' : live ? 'Live' : 'Simulated'}
              </span>
            </TooltipTrigger>
            <TooltipContent>{down ? 'Price feed unavailable: prices are stale' : live ? 'Real prices from CoinGecko, synced every minute' : 'Simulated market (offline mode)'}</TooltipContent>
          </Tooltip>
        )}
        <span className="hidden rounded-full bg-warning/12 px-2.5 py-1 text-xs font-semibold text-warning lg:inline" title="Balances and swaps are simulated: no real money moves">DEMO FUNDS</span>
        <ThemeMenu />
        <Button variant={copilotOpen ? 'secondary' : 'ghost'} size="sm" onClick={onToggleCopilot} aria-pressed={copilotOpen}>
          <Bot /> <span className="hidden sm:inline">Copilot</span>
        </Button>
        <AccountMenu />
      </div>
    </header>
  );
}

function ThemeMenu() {
  const { theme, setTheme } = useTheme();
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="ghost" size="icon-sm" aria-label="Theme">
          <Sun className="dark:hidden" /><Moon className="hidden dark:block" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        {([['dark', 'Dark', Moon], ['light', 'Light', Sun], ['system', 'System', Monitor]] as const).map(([v, label, Icon]) => (
          <DropdownMenuItem key={v} onClick={() => setTheme(v)}>
            <Icon /> {label} {theme === v && <Check className="ml-auto" />}
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

/** Demo identity (sent as X-Demo-User / X-Demo-Role). Each account id has its own simulated wallet. */
function AccountMenu() {
  const { user, role, switchTo } = useIdentity();
  const [open, setOpen] = useState(false);
  const [draft, setDraft] = useState(user);
  const valid = isValidUser(draft);
  const submit = (e: FormEvent) => { e.preventDefault(); if (valid) { switchTo(draft, role); setOpen(false); } };

  return (
    <>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="ghost" className="h-9 gap-2 px-1.5">
            <Avatar className="size-7" aria-hidden="true"><AvatarFallback className="bg-primary text-xs font-semibold text-primary-foreground">{user.slice(0, 2).toUpperCase()}</AvatarFallback></Avatar>
            <span className="sr-only md:not-sr-only md:max-w-28 md:truncate md:text-sm">{user}</span>
            <span className="sr-only">account menu</span>
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-60">
          <DropdownMenuLabel className="font-normal">
            <div className="text-sm font-medium">{user}</div>
            <div className="text-xs text-muted-foreground">Demo account · role {role}</div>
          </DropdownMenuLabel>
          <DropdownMenuSeparator />
          <DropdownMenuGroup>
            <DropdownMenuItem onClick={() => { setDraft(user); setOpen(true); }}><UserRound /> Switch account…</DropdownMenuItem>
          </DropdownMenuGroup>
          <DropdownMenuSeparator />
          <DropdownMenuLabel className="text-xs text-muted-foreground">Role (sent as X-Demo-Role)</DropdownMenuLabel>
          <DropdownMenuRadioGroup value={role} onValueChange={r => switchTo(user, r as DemoRole)}>
            <DropdownMenuRadioItem value="user">user</DropdownMenuRadioItem>
            <DropdownMenuRadioItem value="staff">staff (reads INTERNAL docs)</DropdownMenuRadioItem>
          </DropdownMenuRadioGroup>
        </DropdownMenuContent>
      </DropdownMenu>

      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="sm:max-w-sm">
          <form onSubmit={submit} className="grid gap-4">
            <DialogHeader>
              <DialogTitle>Switch demo account</DialogTitle>
              <DialogDescription>Every account id gets its own simulated wallet with 10,000 USD. No password: this stands in for a real login.</DialogDescription>
            </DialogHeader>
            <div className="grid gap-2">
              <Label htmlFor="demo-user">Account id</Label>
              <Input id="demo-user" value={draft} onChange={e => setDraft(e.target.value)} aria-invalid={!valid} autoFocus />
              {!valid && <p className="text-xs text-destructive">Letters, digits, dot, dash or underscore; 1–40 characters.</p>}
            </div>
            <DialogFooter><Button type="submit" disabled={!valid}>Switch</Button></DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </>
  );
}
