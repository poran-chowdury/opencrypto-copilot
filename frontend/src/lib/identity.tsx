import { createContext, useCallback, useContext, type ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { setDemoRole, setDemoUser, type DemoRole } from './api';
import { useStoredState } from '@/hooks/use-common';

/**
 * The demo identity: sent as X-Demo-User / X-Demo-Role on every request (stand-ins for a JWT's
 * subject and role claim). Switching clears all cached server data, like logging in as someone else.
 */
interface Identity { user: string; role: DemoRole; switchTo: (user: string, role: DemoRole) => void }
const IdentityContext = createContext<Identity>({ user: 'local-demo-user', role: 'user', switchTo: () => {} });

export const isValidUser = (u: string) => /^[\w.-]{1,40}$/.test(u);

export function IdentityProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useStoredState<string>('oc.user', 'local-demo-user');
  const [role, setRole] = useStoredState<DemoRole>('oc.role', 'user');
  const queryClient = useQueryClient();
  setDemoUser(user);
  setDemoRole(role === 'staff' ? 'staff' : 'user');
  const switchTo = useCallback((u: string, r: DemoRole) => {
    if (!isValidUser(u)) return;
    setDemoUser(u); setDemoRole(r);
    setUser(u); setRole(r);
    queryClient.clear();
  }, [queryClient, setRole, setUser]);
  return <IdentityContext.Provider value={{ user, role: role === 'staff' ? 'staff' : 'user', switchTo }}>{children}</IdentityContext.Provider>;
}

export const useIdentity = () => useContext(IdentityContext);
