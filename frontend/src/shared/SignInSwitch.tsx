import { Link } from 'react-router-dom';

/** Top of the sign-in page: staff sign in with email, patients and families with their mobile number. */
export function SignInSwitch({ current }: { current: 'clinic' | 'patient' }) {
  return (
    <nav className="signin-switch" aria-label="Who is signing in">
      <Link to="/" replace aria-current={current === 'clinic' ? 'page' : undefined}>Clinic staff</Link>
      <Link to="/my" replace aria-current={current === 'patient' ? 'page' : undefined}>Patient / family</Link>
    </nav>
  );
}
