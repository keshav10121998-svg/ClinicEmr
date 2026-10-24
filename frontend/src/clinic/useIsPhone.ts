import { useEffect, useState } from 'react';

const QUERY = '(max-width: 759px)';

/** True on phone-width screens, where pages switch to tabs and bottom action bars. */
export function useIsPhone() {
  const [phone, setPhone] = useState(() => window.matchMedia(QUERY).matches);
  useEffect(() => {
    const m = window.matchMedia(QUERY);
    const on = () => setPhone(m.matches);
    m.addEventListener('change', on);
    return () => m.removeEventListener('change', on);
  }, []);
  return phone;
}
