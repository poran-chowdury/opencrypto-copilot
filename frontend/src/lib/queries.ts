import { useQuery } from '@tanstack/react-query';
import { api, type Asset } from './api';

/** The whole catalogue (13 assets) with latest prices; shared by the ticker, search, pickers and icons. */
export function useCatalogue() {
  return useQuery({
    queryKey: ['catalogue'],
    queryFn: () => api.assets({ size: 100, sort: 'marketCap,desc', sparkline: true }),
    staleTime: 20_000,
    refetchInterval: 30_000,
  });
}

export function useAssetMap(): Record<string, Asset> {
  const items = useCatalogue().data?.items ?? [];
  return Object.fromEntries(items.map(a => [a.symbol, a]));
}

export function useMarketStatus(refetchInterval = 15_000) {
  return useQuery({ queryKey: ['market-status'], queryFn: api.marketStatus, refetchInterval });
}
