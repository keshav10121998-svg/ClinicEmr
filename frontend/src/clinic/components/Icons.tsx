/** Small line icons for the phone tab bar. They take the text colour. */
const base = { width: 24, height: 24, viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', strokeWidth: 1.8,
  strokeLinecap: 'round' as const, strokeLinejoin: 'round' as const, 'aria-hidden': true };

export const PeopleIcon = () => (
  <svg {...base}><circle cx="9" cy="8" r="3.2" /><path d="M3 19c0-3.3 2.7-5.5 6-5.5s6 2.2 6 5.5" /><circle cx="17" cy="9" r="2.4" /><path d="M16 13.6c2.7.2 5 2 5 4.9" /></svg>
);
export const StaffIcon = () => (
  <svg {...base}><rect x="4" y="3.5" width="16" height="17" rx="2.5" /><circle cx="12" cy="10" r="2.6" /><path d="M7.5 17c.7-2 2.4-3.1 4.5-3.1s3.8 1.1 4.5 3.1" /></svg>
);
export const ExitIcon = () => (
  <svg {...base}><path d="M14 4h4a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-4" /><path d="M10 16l-4-4 4-4" /><path d="M6 12h10" /></svg>
);
