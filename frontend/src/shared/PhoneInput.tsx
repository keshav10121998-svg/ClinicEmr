import type { InputHTMLAttributes } from 'react';

/** Indian mobile numbers: exactly 10 digits, starting with 6, 7, 8 or 9. */
export const MOBILE_PATTERN = '[6-9][0-9]{9}';

export function isMobile(value: string) {
  return new RegExp(`^${MOBILE_PATTERN}$`).test(value);
}

/** Keeps only the 10 digits, also from pasted numbers like "+91 98765-43210" or "098765 43210". */
export function toMobileDigits(raw: string) {
  let d = raw.replace(/\D/g, '');
  if (d.length === 12 && d.startsWith('91')) d = d.slice(2);
  else if (d.length === 11 && d.startsWith('0')) d = d.slice(1);
  return d.slice(0, 10);
}

type Props = Omit<InputHTMLAttributes<HTMLInputElement>, 'value' | 'onChange' | 'type'> & {
  value: string;
  onChange: (digits: string) => void;
};

/**
 * A mobile number box that only takes digits and stops at 10. While a number is part-typed it says how many
 * digits are missing; the form won't submit until there are exactly 10.
 */
export function PhoneInput({ value, onChange, placeholder = '98765 43210', ...rest }: Props) {
  const short = value.length > 0 && value.length < 10;
  const badStart = value.length > 0 && !/^[6-9]/.test(value);
  return (
    <>
      <input {...rest} type="tel" inputMode="numeric" autoComplete={rest.autoComplete ?? 'tel-national'}
             pattern={MOBILE_PATTERN} title="10-digit mobile number" placeholder={placeholder}
             value={value} onChange={(e) => onChange(toMobileDigits(e.target.value))} />
      {(short || badStart) && (
        <span className="small field-hint" role="status">
          {badStart ? 'Mobile numbers start with 6, 7, 8 or 9.' : `${10 - value.length} more ${10 - value.length === 1 ? 'digit' : 'digits'} needed (10 in total).`}
        </span>
      )}
    </>
  );
}
