package com.clinicemr.prescription;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.clinicemr.clinical.EncounterService;
import com.clinicemr.clinical.SignedSnapshot;
import com.clinicemr.shared.audit.AuditService;
import com.clinicemr.shared.error.ApiException;
import com.clinicemr.shared.security.ClinicContext;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PrescriptionController {

    private final EncounterService encounters;
    private final PrescriptionPdfRenderer renderer;
    private final JdbcClient jdbc;
    private final AuditService audit;

    public PrescriptionController(EncounterService encounters, PrescriptionPdfRenderer renderer, JdbcClient jdbc,
                                  AuditService audit) {
        this.encounters = encounters;
        this.renderer = renderer;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** Printable prescription for a signed visit. Front desk, nurses and doctors of the clinic can print it. */
    @GetMapping("/api/v1/clinic/encounters/{id}/prescription.pdf")
    @Transactional
    public ResponseEntity<byte[]> pdf(ClinicContext ctx, @PathVariable UUID id,
                                      @RequestParam(defaultValue = "A5") String paper) {
        PrescriptionPdfRenderer.Paper size;
        try {
            size = PrescriptionPdfRenderer.Paper.valueOf(paper.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("INVALID_PAPER", "Paper must be A4 or A5.");
        }
        SignedSnapshot snapshot = encounters.latestSnapshot(ctx, id);
        byte[] pdf = renderer.render(snapshot, size);

        jdbc.sql("UPDATE prescription SET printed_at = now() WHERE encounter_id = :id").param("id", id).update();
        audit.staff(ctx.staffId(), ctx.clinicId(), "PRESCRIPTION_PRINTED", "encounter", id.toString(),
                Map.of("version", snapshot.version(), "paper", size.name()));

        String filename = "prescription-" + snapshot.patient().mrn() + "-v" + snapshot.version() + ".pdf";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(filename).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(pdf);
    }
}
