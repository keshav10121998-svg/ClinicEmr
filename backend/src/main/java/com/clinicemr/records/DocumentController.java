package com.clinicemr.records;

import java.util.List;
import java.util.UUID;

import com.clinicemr.records.DocumentDtos.DocumentDetail;
import com.clinicemr.records.DocumentDtos.DocumentSummary;
import com.clinicemr.records.DocumentDtos.StartUploadRequest;
import com.clinicemr.records.DocumentDtos.StartUploadResponse;
import com.clinicemr.shared.security.ClinicContext;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/clinic/patients/{patientId}/documents")
public class DocumentController {

    private final DocumentService documents;

    public DocumentController(DocumentService documents) {
        this.documents = documents;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public StartUploadResponse start(ClinicContext ctx, @PathVariable UUID patientId,
                                     @Valid @RequestBody StartUploadRequest req) {
        return documents.startUpload(ctx, patientId, req);
    }

    @PostMapping("/{documentId}/complete")
    public DocumentSummary complete(ClinicContext ctx, @PathVariable UUID patientId, @PathVariable UUID documentId) {
        return documents.completeUpload(ctx, patientId, documentId);
    }

    @GetMapping
    public List<DocumentSummary> list(ClinicContext ctx, @PathVariable UUID patientId) {
        return documents.list(ctx, patientId);
    }

    @GetMapping("/{documentId}")
    public DocumentDetail get(ClinicContext ctx, @PathVariable UUID patientId, @PathVariable UUID documentId) {
        return documents.get(ctx, patientId, documentId);
    }
}
