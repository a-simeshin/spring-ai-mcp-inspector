import { forwardRef, type InputHTMLAttributes } from 'react';
import { Check } from 'lucide-react';
import clsx from 'clsx';

export interface CheckboxProps
  extends Omit<InputHTMLAttributes<HTMLInputElement>, 'onChange' | 'type'> {
  checked?: boolean;
  onCheckedChange?: (v: boolean) => void;
}

export const Checkbox = forwardRef<HTMLInputElement, CheckboxProps>(
  ({ checked, onCheckedChange, className, disabled, ...rest }, ref) => (
    <span
      data-state={checked ? 'checked' : 'unchecked'}
      data-disabled={disabled ? '' : undefined}
      className={clsx(
        'peer relative inline-flex h-4 w-4 shrink-0 items-center justify-center rounded-sm border border-primary',
        'ring-offset-background',
        'focus-within:outline-none focus-within:ring-2 focus-within:ring-ring focus-within:ring-offset-2',
        'disabled:cursor-not-allowed disabled:opacity-50',
        checked && 'bg-primary text-primary-foreground',
        className,
      )}
    >
      <input
        ref={ref}
        type="checkbox"
        checked={!!checked}
        disabled={disabled}
        onChange={(e) => onCheckedChange?.(e.target.checked)}
        className="absolute inset-0 h-full w-full cursor-pointer appearance-none opacity-0 disabled:cursor-not-allowed"
        {...rest}
      />
      {checked && <Check className="h-3.5 w-3.5 pointer-events-none" strokeWidth={3} />}
    </span>
  ),
);
Checkbox.displayName = 'Checkbox';
