import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { useCatalogue } from '@/lib/queries';
import { cn } from '@/lib/utils';
import { CoinIcon } from './primitives';

/** Asset picker with logos. `exclude` hides symbols that make no sense (e.g. the other side of a swap). */
export function AssetSelect({ value, onChange, id, label, exclude = [], className }: {
  value: string; onChange: (symbol: string) => void; id?: string; label: string; exclude?: string[]; className?: string;
}) {
  const assets = (useCatalogue().data?.items ?? []).filter(a => !exclude.includes(a.symbol) || a.symbol === value);
  return (
    <Select value={value} onValueChange={onChange}>
      <SelectTrigger id={id} aria-label={label} className={cn('[&_[data-name]]:hidden', className)}>
        <SelectValue placeholder="Asset" />
      </SelectTrigger>
      <SelectContent position="popper" className="max-h-72">
        {assets.map(a => (
          <SelectItem key={a.symbol} value={a.symbol}>
            <CoinIcon symbol={a.symbol} imageUrl={a.imageUrl} size={18} />
            <span className="font-medium">{a.symbol}</span>
            <span data-name className="text-muted-foreground">{a.name}</span>
          </SelectItem>
        ))}
        {assets.length === 0 && <SelectItem value={value}>{value}</SelectItem>}
      </SelectContent>
    </Select>
  );
}
