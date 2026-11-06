import { useEffect, useState } from 'react';
import { api } from '../api';
import { date } from '../format';
import { rupees } from '../payments';
import type { Plan } from '../types';

/**
 * The clinic's access: whether the super admin has switched it on and until when. Read again when the app is opened
 * or comes back to the front, so a renewal shows up without signing out.
 */
export function usePlan() {
  const [plan, setPlan] = useState<Plan | null>();
  useEffect(() => {
    const load = () => api.get<Plan>('/clinic/plan').then(setPlan).catch(() => setPlan(null));
    load();
    const onShow = () => { if (document.visibilityState === 'visible') load(); };
    document.addEventListener('visibilitychange', onShow);
    return () => document.removeEventListener('visibilitychange', onShow);
  }, []);
  return plan ?? null;
}

/**
 * At the top of every screen while the clinic is read-only (not switched on, or its time has ended), and for the last
 * week before it ends. Shows nothing the rest of the time.
 */
export function PlanBanner() {
  const plan = usePlan();
  if (!plan) return null;
  if (plan.status === 'NOT_ENABLED') {
    return (
      <div className="plan-banner blocked" role="alert">
        <b>Not switched on yet.</b> You can look at records, but you can't add or change anything. Please contact ClinicEMR to start.
      </div>
    );
  }
  if (plan.status === 'EXPIRED') {
    return (
      <div className="plan-banner blocked" role="alert">
        <b>Your plan ended on {date(plan.accessUntil)}.</b> You can still view patients and prescriptions, but new visits and
        changes are blocked until it is renewed. Please contact ClinicEMR to renew.
      </div>
    );
  }
  if (plan.status === 'EXPIRING') {
    const days = plan.daysLeft ?? 0;
    return (
      <div className="plan-banner soon" role="status">
        Your plan ends {days === 0 ? 'today' : `in ${days} ${days === 1 ? 'day' : 'days'}`} (on {date(plan.accessUntil)}). After that new visits
        are blocked. Please contact ClinicEMR to renew.
      </div>
    );
  }
  return null;
}

const STATUS: Record<Plan['status'], string> = { ACTIVE: 'Active', EXPIRING: 'Ending soon', EXPIRED: 'Ended', NOT_ENABLED: 'Not switched on' };

/** What was agreed with the clinic, for its admin: the monthly price (or free) and the day access ends. */
export function PlanCard() {
  const plan = usePlan();
  if (!plan) return null;
  return (
    <div className="card plan-card">
      <div className="between">
        <h2>Your plan</h2>
        <span className={`chip ${plan.blocked ? 'allergy' : 'ok'}`}>{STATUS[plan.status]}</span>
      </div>
      <ul className="plan-lines">
        <li><span>Price</span><b>{plan.monthlyPrice === undefined ? '' : plan.monthlyPrice === 0 ? 'Free' : `${rupees(plan.monthlyPrice)} a month`}</b></li>
        <li><span>{plan.blocked ? 'Ended on' : 'Valid until'}</span><b>{plan.accessUntil ? date(plan.accessUntil) : 'Not switched on'}</b></li>
      </ul>
      <p className="small muted" style={{ margin: 0 }}>
        The terms are agreed with ClinicEMR in person. When the plan ends you can still view records, but new visits and
        changes are blocked until it is renewed.
      </p>
    </div>
  );
}
