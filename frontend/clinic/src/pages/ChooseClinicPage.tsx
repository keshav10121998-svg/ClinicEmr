import { useSession } from '../session';
import { roleLabel } from '../App';

export function ChooseClinicPage() {
  const { session, chooseClinic, logout } = useSession();
  return (
    <div className="auth">
      <div className="card">
        <h1>Choose a clinic</h1>
        {session!.clinics.length === 0 && <p className="muted">You aren't a member of any clinic yet.</p>}
        {session!.clinics.map((c) => (
          <button key={c.clinicId} onClick={() => chooseClinic(c.clinicId)} style={{ textAlign: 'left' }}>
            <b>{c.clinicName}</b>
            <div className="small muted">{c.roles.map(roleLabel).join(', ')}</div>
          </button>
        ))}
        <button className="link" onClick={logout}>Sign out</button>
      </div>
    </div>
  );
}
