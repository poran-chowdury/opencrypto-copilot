import { createContext, useCallback, useContext, useEffect, type ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { api, getAuthToken, setAuthToken, setDemoRole, setDemoUser, type DemoRole } from './api';
import { useStoredState } from '@/hooks/use-common';

export interface PresetUser {
  id: string;
  name: string;
  tier: 'STANDARD' | 'PREMIUM' | 'PRIVILEGED';
  role: DemoRole;
  description: string;
}

export const PRESET_USERS: PresetUser[] = [
  { id: 'alice', name: 'Alice', tier: 'PREMIUM', role: 'user', description: 'Premium trader ($10k portfolio)' },
  { id: 'bob', name: 'Bob', tier: 'STANDARD', role: 'user', description: 'Standard starter ($10k portfolio)' },
  { id: 'user-1', name: 'User 1', tier: 'PRIVILEGED', role: 'staff', description: 'Privileged Staff / Trader' },
  { id: 'demo', name: 'Demo User', tier: 'STANDARD', role: 'user', description: 'Standard demo account' },
];

/**
 * The authenticated identity: backed by OIDC RS256 JWT tokens issued by identity-service.
 * Claims (subject, tier, roles) are verified at the API gateway and propagated downstream.
 */
interface Identity {
  user: string;
  role: DemoRole;
  tier: string;
  switchTo: (user: string, role: DemoRole) => void;
}

const IdentityContext = createContext<Identity>({
  user: 'demo',
  role: 'user',
  tier: 'STANDARD',
  switchTo: () => {},
});

export const isValidUser = (u: string) => /^[\w.-]{1,40}$/.test(u);

export function IdentityProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useStoredState<string>('oc.user', 'demo');
  const [role, setRole] = useStoredState<DemoRole>('oc.role', 'user');
  const [tier, setTier] = useStoredState<string>('oc.tier', 'STANDARD');
  const queryClient = useQueryClient();

  setDemoUser(user);
  setDemoRole(role === 'staff' ? 'staff' : 'user');

  // Auto-login on startup to ensure a valid RS256 JWT is active
  useEffect(() => {
    if (!getAuthToken()) {
      api.login(user, 'password')
        .then(res => {
          setAuthToken(res.token);
          if (res.tier) setTier(res.tier);
        })
        .catch(e => console.warn('Initial token acquisition error:', e));
    }
  }, [user, setTier]);

  const switchTo = useCallback((u: string, r: DemoRole) => {
    if (!isValidUser(u)) return;
    setDemoUser(u);
    setDemoRole(r);
    setUser(u);
    setRole(r);

    // Acquire new JWT token from identity-service
    api.login(u, 'password')
      .then(res => {
        setAuthToken(res.token);
        if (res.tier) setTier(res.tier);
        queryClient.clear();
      })
      .catch(e => {
        console.warn('Login failed for switch user:', u, e);
        queryClient.clear();
      });
  }, [queryClient, setRole, setTier, setUser]);

  return (
    <IdentityContext.Provider value={{ user, role: role === 'staff' ? 'staff' : 'user', tier, switchTo }}>
      {children}
    </IdentityContext.Provider>
  );
}

export const useIdentity = () => useContext(IdentityContext);
