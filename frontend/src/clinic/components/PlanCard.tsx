import { useEffect, useState } from 'react';
import { api } from '../api';
import { date } from '../format';
import { rupees } from '../payments';
import { useSession } from '../session';
import type { Plan } from '../types';

export const STAFF_PRICE = 20;

const STAGE: Record<Plan['stage'], string> = { FREE_MONTH: 'Free month', LAUNCH: 'Launch price', STANDARD: 'Standard price' };

/** The clinic's plan, for clinic admins only (null for everyone else, and while loading or if it can't be loaded). */
export function usePlan() {
  const { has } = useSession();
  const admin = has('ADMIN');
  const [plan, setPlan] = useState<Plan | null>();
  useEffect(() => {
    if (!admin) return;
    api.get<Plan>('/clinic/plan').then(setPlan).catch(() => setPlan(null));
  }, [admin]);
  return admin ? plan ?? null : null;
}

/** What the clinic pays this month, what it is made of, how long it is paid for, and when the price changes. */
export function PlanCard() {
  const plan = usePlan();
  if (!plan) return null;
  return (
    <div className="card plan-card">
      <div className="between">
        <h2>Your plan</h2>
        <span className={`chip ${plan.status === 'DUE' ? 'allergy' : 'ok'}`}>{statusText(plan)}</span>
      </div>
      <div className="plan-price">
        <span className="plan-amount">{rupees(plan.total)}</span>
        <span className="muted">a month · {STAGE[plan.stage]}</span>
      </div>
      <ul className="plan-lines">
        <li><span>{plan.stage === 'FREE_MONTH' ? 'First month' : 'Clinic'}</span><b>{rupees(plan.basePrice)}</b></li>
        <li>
          <span>{plan.billableStaff} {plan.billableStaff === 1 ? 'staff member' : 'staff members'} × {rupees(STAFF_PRICE)}
            {plan.stage === 'FREE_MONTH' && plan.billableStaff > 0 ? ' (free this month)' : ''}</span>
          <b>{rupees(plan.staffCharge)}</b>
        </li>
      </ul>
      <p className="small muted" style={{ margin: 0 }}>
        The first person (the owner) is included. Each other active staff member adds {rupees(STAFF_PRICE)} a month; switching someone off stops it.
      </p>
      {plan.nextChange && (
        <p className="plan-change">
          On <b>{date(plan.nextChange.on)}</b> ({plan.nextChange.daysAway} {plan.nextChange.daysAway === 1 ? 'day' : 'days'} from now) the price
          becomes <b>{rupees(plan.nextChange.basePrice)}</b> a month{plan.billableStaff > 0 ? ` plus ${rupees(plan.billableStaff * STAFF_PRICE)} for staff` : ''}.
        </p>
      )}
      <p className="small muted" style={{ margin: 0 }}>
        {plan.status === 'DUE'
          ? `Your plan ran out on ${date(plan.coveredUntil)}. Pay ${rupees(plan.nextAmount)} for the next month to keep it paid.`
          : `${plan.status === 'FREE_MONTH' ? 'Free until' : 'Paid until'} ${date(plan.coveredUntil)}. After that: ${rupees(plan.nextAmount)} a month.`}
        {' '}Nothing stops working if a payment is late.
      </p>
    </div>
  );
}

function statusText(p: Plan) {
  return p.status === 'FREE_MONTH' ? `Free · ${p.daysLeft} ${p.daysLeft === 1 ? 'day' : 'days'} left`
    : p.status === 'PAID' ? 'Paid' : 'Payment due';
}

/**
 * A short heads-up on the Overview for clinic admins: payment due, the plan ending within a week, or a price change
 * within 30 days. Shows nothing the rest of the time.
 */
export function PlanNotice() {
  const plan = usePlan();
  if (!plan) return null;
  const change = plan.nextChange && plan.nextChange.daysAway <= 30 ? plan.nextChange : undefined;
  const ending = plan.status !== 'DUE' && plan.daysLeft <= 7;
  if (plan.status !== 'DUE' && !ending && !change) return null;
  return (
    <div className={`alert ${plan.status === 'DUE' ? 'error' : 'info'}`}>
      {plan.status === 'DUE' && <>Your plan ran out on {date(plan.coveredUntil)}. Pay {rupees(plan.nextAmount)} to continue. </>}
      {ending && <>Your {plan.status === 'FREE_MONTH' ? 'free month' : 'plan'} ends on {date(plan.coveredUntil)}; the next month is {rupees(plan.nextAmount)}. </>}
      {change && <>From {date(change.on)} the price is {rupees(change.basePrice)} a month{plan.billableStaff > 0 ? ' plus staff' : ''}. </>}
    </div>
  );
}
