import { ToggleGroup as ToggleGroupPrimitive } from 'radix-ui';
import { cn } from '@/lib/utils';

/**
 * Segmented control for filters and modes (All / Crypto / …, 1H / 24H / …).
 * Not Tabs: tabs promise tab panels (aria-controls), a filter just changes the data shown.
 * A single-select toggle group is the right semantics and passes axe's ARIA checks.
 */
export function Segmented<T extends string>({ value, onChange, options, label, className, itemClassName }: {
  value: T; onChange: (v: T) => void; options: readonly (readonly [T, React.ReactNode])[]; label: string; className?: string; itemClassName?: string;
}) {
  return (
    <ToggleGroupPrimitive.Root type="single" value={value} onValueChange={v => v && onChange(v as T)} aria-label={label}
      className={cn('inline-flex h-8 w-fit items-center rounded-lg bg-muted p-[3px] text-muted-foreground', className)}>
      {options.map(([v, text]) => (
        <ToggleGroupPrimitive.Item key={v} value={v}
          className={cn('inline-flex h-full flex-1 items-center justify-center rounded-md px-2.5 text-sm font-medium whitespace-nowrap transition-colors outline-none',
            'hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50',
            'data-[state=on]:bg-background data-[state=on]:text-foreground data-[state=on]:shadow-sm dark:data-[state=on]:bg-input/50', itemClassName)}>
          {text}
        </ToggleGroupPrimitive.Item>
      ))}
    </ToggleGroupPrimitive.Root>
  );
}
