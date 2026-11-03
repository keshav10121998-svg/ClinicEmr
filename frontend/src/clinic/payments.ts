import { useCallback, useEffect, useState } from 'react';
import { api } from './api';
import type { PaymentDue, Role } from './types';

/** Fired after a payment is saved or settled, so the reminder count and lists refresh. */
export const PAYMENTS_CHANGED = 'clinic-emr:payments-changed';

export function paymentsChanged() {
  window.dispatchEvent(new Event(PAYMENTS_CHANGED));
}

/** Doctors are reminded about their own patients; admins and front desk see the whole clinic's. */
export function onlyMine(has: (...roles: Role[]) => boolean) {
  return has('DOCTOR') && !has('ADMIN', 'FRONT_DESK');
}

/** Balances due today or earlier: the "Money to collect" reminder. Refreshes every 5 minutes and after changes. */
export function useDuePayments(mine: boolean) {
  const [dues, setDues] = useState<PaymentDue[]>();
  const load = useCallback(() => {
    api.get<PaymentDue[]>(`/clinic/payments/due?when=now&mine=${mine}`).then(setDues).catch(() => setDues([]));
  }, [mine]);
  useEffect(() => {
    load();
    const t = window.setInterval(load, 5 * 60 * 1000);
    window.addEventListener(PAYMENTS_CHANGED, load);
    return () => {
      window.clearInterval(t);
      window.removeEventListener(PAYMENTS_CHANGED, load);
    };
  }, [load]);
  return { dues, reload: load };
}

export function rupees(n: number) {
  return '₹' + n.toLocaleString('en-IN');
}

export function dueLabel(overdueDays: number) {
  if (overdueDays === 0) return 'due today';
  if (overdueDays > 0) return `${overdueDays} ${overdueDays === 1 ? 'day' : 'days'} late`;
  return `due in ${-overdueDays} ${overdueDays === -1 ? 'day' : 'days'}`;
}
