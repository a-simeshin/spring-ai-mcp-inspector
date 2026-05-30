import { forwardRef, type LabelHTMLAttributes } from 'react';
import clsx from 'clsx';

export type LabelProps = LabelHTMLAttributes<HTMLLabelElement>;

export const Label = forwardRef<HTMLLabelElement, LabelProps>(({ className, ...rest }, ref) => (
  <label
    ref={ref}
    className={clsx(
      'text-sm font-medium leading-none select-none peer-disabled:cursor-not-allowed peer-disabled:opacity-70',
      className,
    )}
    {...rest}
  />
));
Label.displayName = 'Label';
