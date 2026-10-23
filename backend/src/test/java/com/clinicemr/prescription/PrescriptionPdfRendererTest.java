package com.clinicemr.prescription;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.clinicemr.clinical.EncounterContent;
import com.clinicemr.clinical.SignedSnapshot;
import org.junit.jupiter.api.Test;

class PrescriptionPdfRendererTest {

    private final PrescriptionPdfRenderer renderer = new PrescriptionPdfRenderer();

    static SignedSnapshot sample(int version, String reason, String advice) {
        EncounterContent content = new EncounterContent(
                "Fever 3 days, sore throat", null, null, "Throat congested",
                "Nebulisation given in clinic", advice, "Patient anxious about dengue <private>",
                new EncounterContent.Vitals(130, 85, 96, new BigDecimal("101.20"), 97, new BigDecimal("68"), null, null, null),
                List.of(new EncounterContent.DiagnosisEntry("ICD10", "J02.9", "Acute pharyngitis, unspecified", "PRIMARY", false),
                        new EncounterContent.DiagnosisEntry("ICD10", "E11.9", "Type 2 diabetes mellitus without complications", "SECONDARY", true)),
                List.of(new EncounterContent.RxItem(null, "Paracetamol 650 mg tablet", "Paracetamol", "1 tablet", "1-0-1", "oral", "after food", 3, false, "Only if fever above 100 °F"),
                        new EncounterContent.RxItem(null, "Metformin 500 mg tablet", "Metformin", "1 tablet", "1-0-1", "oral", "after food", null, true, null)),
                List.of(new EncounterContent.InvestigationEntry(null, "CBC"), new EncounterContent.InvestigationEntry(null, "Dengue NS1 antigen")),
                new EncounterContent.FollowUpEntry(LocalDate.of(2026, 10, 25), "Review with reports"));
        return new SignedSnapshot(UUID.fromString("2f6c1e1a-0000-4000-8000-000000000001"), version, "OPD",
                Instant.parse("2026-10-22T05:40:00Z"), Instant.parse("2026-10-22T06:12:07Z"), reason,
                new SignedSnapshot.PatientInfo(UUID.randomUUID(), "P000001", "Kamla Devi", "F", LocalDate.of(1950, 3, 1), true, 76),
                new SignedSnapshot.ClinicInfo(1, "City Care Clinic", "12 MG Road", "Bengaluru", "Karnataka", "560038", "+918012345678"),
                new SignedSnapshot.DoctorInfo(1, "Asha Mehta", "MBBS, MD (General Medicine)", "KMC-12345", "Karnataka Medical Council", "Physician"),
                content);
    }

    @Test
    void neverPrintsThePrivateNoteAndEscapesText() {
        String html = renderer.html(sample(1, null, "Rest & fluids <b>now</b>"), PrescriptionPdfRenderer.Paper.A5);
        assertThat(html).doesNotContain("anxious about dengue");
        assertThat(html).contains("Rest &amp; fluids &lt;b&gt;now&lt;/b&gt;");
        assertThat(html).contains("Dr. Asha Mehta").contains("Reg. No. KMC-12345").contains("76 yrs (approx.) / Female");
        assertThat(html).contains("BP 130/85 mmHg").contains("Temp 101.2 °F").contains("Continue");
        assertThat(html).doesNotContain("Amended");
        assertThat(html).contains("Treatment given").contains("Nebulisation given in clinic");
    }

    @Test
    void medicinesWithoutFrequencyOrDurationPrintBlank() {
        var bare = new EncounterContent.RxItem(null, "Antacid syrup", null, null, null, null, null, null, false, null);
        assertThat(PrescriptionPdfRenderer.durationLabel(bare)).isEmpty();
        SignedSnapshot s = sample(1, null, null);
        SignedSnapshot withBare = new SignedSnapshot(s.encounterId(), 1, s.visitType(), s.startedAt(), s.signedAt(), null,
                s.patient(), s.clinic(), s.doctor(),
                new EncounterContent(null, null, null, null, null, null, null, null, List.of(), List.of(bare), List.of(), null));
        assertThat(renderer.html(withBare, PrescriptionPdfRenderer.Paper.A5)).contains("Antacid syrup").doesNotContain("null");
        assertThat(new String(renderer.render(withBare, PrescriptionPdfRenderer.Paper.A5), 0, 5)).isEqualTo("%PDF-");
    }

    @Test
    void marksAmendedPrescriptions() {
        String html = renderer.html(sample(2, "Extended paracetamol to 5 days", null), PrescriptionPdfRenderer.Paper.A4);
        assertThat(html).contains("Amended (version 2)").contains("Extended paracetamol to 5 days");
    }

    @Test
    void rendersAPdf() throws Exception {
        byte[] pdf = renderer.render(sample(2, "Extended paracetamol to 5 days", "Warm saline gargles.\nPlenty of fluids."),
                PrescriptionPdfRenderer.Paper.A5);
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        // Kept for manual review of the layout
        Files.write(Path.of("target", "sample-prescription.pdf"), pdf);
    }
}
