import { NavLink, useLocation } from 'react-router-dom';
import { ShieldCheck } from 'lucide-react';
import {
  Sidebar, SidebarContent, SidebarFooter, SidebarGroup, SidebarGroupContent, SidebarGroupLabel, SidebarHeader,
  SidebarMenu, SidebarMenuButton, SidebarMenuItem, SidebarRail, useSidebar,
} from '@/components/ui/sidebar';
import { NAV_GROUPS } from './nav';

export function Logo({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 32 32" aria-hidden="true" className={className}>
      <defs>
        <linearGradient id="oc-logo" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor="#5b82ff" /><stop offset="1" stopColor="#2ebd85" />
        </linearGradient>
      </defs>
      <rect width="32" height="32" rx="9" fill="url(#oc-logo)" />
      <path d="M7 21l6-7 5 4 7-9" fill="none" stroke="#fff" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

export function AppSidebar() {
  const { pathname } = useLocation();
  const { setOpenMobile } = useSidebar();
  const isActive = (to: string) => (to === '/' ? pathname === '/' || pathname.startsWith('/assets') : pathname.startsWith(to));

  return (
    <Sidebar collapsible="icon">
      <SidebarHeader>
        <SidebarMenu>
          <SidebarMenuItem>
            <SidebarMenuButton size="lg" asChild tooltip="OpenCrypto">
              <NavLink to="/" onClick={() => setOpenMobile(false)}>
                <Logo className="size-8 shrink-0" />
                <span className="flex flex-col leading-tight">
                  <span className="font-semibold tracking-tight">OpenCrypto</span>
                  <span className="text-xs text-sidebar-foreground/70">Pro · demo exchange</span>
                </span>
              </NavLink>
            </SidebarMenuButton>
          </SidebarMenuItem>
        </SidebarMenu>
      </SidebarHeader>

      <SidebarContent>
        <nav aria-label="Main">
          {NAV_GROUPS.map(group => (
            <SidebarGroup key={group.label}>
              <SidebarGroupLabel>{group.label}</SidebarGroupLabel>
              <SidebarGroupContent>
                <SidebarMenu>
                  {group.items.map(item => (
                    <SidebarMenuItem key={item.to}>
                      <SidebarMenuButton asChild isActive={isActive(item.to)} tooltip={item.label}>
                        <NavLink to={item.to} end={item.to === '/'} onClick={() => setOpenMobile(false)}>
                          <item.icon />
                          <span>{item.label}</span>
                        </NavLink>
                      </SidebarMenuButton>
                    </SidebarMenuItem>
                  ))}
                </SidebarMenu>
              </SidebarGroupContent>
            </SidebarGroup>
          ))}
        </nav>
      </SidebarContent>

      <SidebarFooter>
        <div className="flex items-start gap-2 rounded-lg border border-sidebar-border bg-sidebar-accent/60 p-3 text-xs text-sidebar-foreground/80 group-data-[collapsible=icon]:hidden">
          <ShieldCheck className="mt-0.5 size-4 shrink-0 text-up" aria-hidden="true" />
          <p>Demo funds only. Real market prices, simulated balances and swaps.</p>
        </div>
      </SidebarFooter>
      <SidebarRail />
    </Sidebar>
  );
}
