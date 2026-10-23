package com.clinicemr.patientapp;

import java.util.List;
import java.util.UUID;

import com.clinicemr.records.DocumentDtos.DocumentDetail;
import com.clinicemr.records.DocumentDtos.StartUploadRequest;
import com.clinicemr.records.DocumentDtos.StartUploadResponse;
import com.clinicemr.shared.security.PatientContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/patient/profiles/{profileId}")
public class PatientRecordsController {

    private final PatientRecordsService records;

    public PatientRecordsController(PatientRecordsService records) {
        this.records = records;
    }

    @GetMapping("/visits")
    public List<PatientRecordsService.VisitItem> visits(PatientContext p, @PathVariable UUID profileId) {
        return records.visits(p, profileId);
    }

    @GetMapping("/visits/{encounterId}")
    public PatientRecordsService.VisitDetail visit(PatientContext p, @PathVariable UUID profileId,
                                                   @PathVariable UUID encounterId) {
        return records.visit(p, profileId, encounterId);
    }

    @GetMapping("/visits/{encounterId}/prescription.pdf")
    public ResponseEntity<byte[]> prescription(PatientContext p, @PathVariable UUID profileId,
                                               @PathVariable UUID encounterId) {
        PatientRecordsService.PrescriptionPdf pdf = records.prescriptionPdf(p, profileId, encounterId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(pdf.filename()).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(pdf.bytes());
    }

    @GetMapping("/documents")
    public List<PatientRecordsService.PatientDocument> documents(PatientContext p, @PathVariable UUID profileId) {
        return records.documents(p, profileId);
    }

    @PostMapping("/documents")
    @ResponseStatus(HttpStatus.CREATED)
    public StartUploadResponse upload(PatientContext p, @PathVariable UUID profileId,
                                      @Valid @RequestBody StartUploadRequest req) {
        return records.startUpload(p, profileId, req);
    }

    @PutMapping("/documents/{documentId}/pages/{pageNo}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void uploadPage(PatientContext p, @PathVariable UUID profileId, @PathVariable UUID documentId,
                           @PathVariable int pageNo, HttpServletRequest request) throws java.io.IOException {
        records.uploadPage(p, profileId, documentId, pageNo, request.getContentType(), request.getInputStream());
    }

    @PostMapping("/documents/{documentId}/complete")
    public PatientRecordsService.PatientDocument complete(PatientContext p, @PathVariable UUID profileId,
                                                          @PathVariable UUID documentId) {
        return records.completeUpload(p, profileId, documentId);
    }

    @GetMapping("/documents/{documentId}")
    public DocumentDetail document(PatientContext p, @PathVariable UUID profileId, @PathVariable UUID documentId) {
        return records.document(p, profileId, documentId);
    }

    @DeleteMapping("/documents/{documentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(PatientContext p, @PathVariable UUID profileId, @PathVariable UUID documentId) {
        records.deleteUpload(p, profileId, documentId);
    }
}
