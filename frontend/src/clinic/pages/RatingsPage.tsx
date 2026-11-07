import { PatientRatings, RateApp } from '../components/Ratings';

/** What patients say about the clinic, and the clinic's own rating of ClinicEMR. Admins and doctors only. */
export function RatingsPage() {
  return (
    <>
      <h1>Ratings</h1>
      <PatientRatings />
      <RateApp />
    </>
  );
}
