package com.clinicemr.history;

import java.util.List;
import java.util.UUID;

import com.clinicemr.history.HistoryDtos.AddAllergyRequest;
import com.clinicemr.history.HistoryDtos.AllergyList;
import com.clinicemr.history.HistoryDtos.ChangeAllergyStatusRequest;
import com.clinicemr.history.HistoryDtos.LastPrescription;
import com.clinicemr.history.HistoryDtos.PatientSummary;
import com.clinicemr.history.HistoryDtos.ResolveProblemRequest;
import com.clinicemr.history.HistoryDtos.StopMedicineRequest;
import com.clinicemr.history.HistoryDtos.VitalSeries;
import com.clinicemr.shared.security.ClinicContext;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/clinic/patients/{patientId}")
public class PatientHistoryController {

    private final SummaryService summaries;
    private final AllergyService allergies;

    public PatientHistoryController(SummaryService summaries, AllergyService allergies) {
        this.summaries = summaries;
        this.allergies = allergies;
    }

    @GetMapping("/summary")
    public PatientSummary summary(ClinicContext ctx, @PathVariable UUID patientId) {
        return summaries.summary(ctx, patientId);
    }

    @GetMapping("/vitals")
    public List<VitalSeries> vitals(ClinicContext ctx, @PathVariable UUID patientId,
                                    @RequestParam(required = false) String code) {
        return summaries.vitalTrends(ctx, patientId, code);
    }

    @GetMapping("/last-prescription")
    public LastPrescription lastPrescription(ClinicContext ctx, @PathVariable UUID patientId) {
        return summaries.lastPrescription(ctx, patientId);
    }

    @PostMapping("/problems/resolve")
    public PatientSummary resolveProblem(ClinicContext ctx, @PathVariable UUID patientId,
                                         @Valid @RequestBody ResolveProblemRequest req) {
        return summaries.resolveProblem(ctx, patientId, req.problemKey());
    }

    @PostMapping("/medicines/stop")
    public PatientSummary stopMedicine(ClinicContext ctx, @PathVariable UUID patientId,
                                       @Valid @RequestBody StopMedicineRequest req) {
        return summaries.stopMedicine(ctx, patientId, req.medicationKey(), req.reason());
    }

    @GetMapping("/allergies")
    public AllergyList allergies(ClinicContext ctx, @PathVariable UUID patientId) {
        return allergies.list(ctx, patientId);
    }

    @PostMapping("/allergies")
    public AllergyList addAllergy(ClinicContext ctx, @PathVariable UUID patientId,
                                  @Valid @RequestBody AddAllergyRequest req) {
        return allergies.add(ctx, patientId, req);
    }

    @PostMapping("/allergies/none-known")
    public AllergyList noKnownAllergies(ClinicContext ctx, @PathVariable UUID patientId) {
        return allergies.markNoKnownAllergies(ctx, patientId);
    }

    @PostMapping("/allergies/{allergyId}/status")
    public AllergyList changeAllergyStatus(ClinicContext ctx, @PathVariable UUID patientId, @PathVariable UUID allergyId,
                                           @Valid @RequestBody ChangeAllergyStatusRequest req) {
        return allergies.changeStatus(ctx, patientId, allergyId, req);
    }
}
