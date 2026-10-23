package com.clinicemr.clinical;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.clinicemr.clinical.EncounterContent.DiagnosisEntry;
import com.clinicemr.clinical.EncounterContent.InvestigationEntry;
import com.clinicemr.clinical.EncounterContent.RxItem;
import com.clinicemr.clinical.EncounterContent.Vitals;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Writes the structured rows (notes, vitals, diagnoses, prescription, investigations, follow-up) for the latest
 * signed version of a visit. On amendment the rows are replaced; earlier versions live on in encounter_version.
 */
@Component
class ClinicalRowsWriter {

    private final JdbcClient jdbc;

    ClinicalRowsWriter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record VitalRow(String code, String display, BigDecimal value, String unit) {
    }

    /** Vitals as LOINC-coded observations with UCUM units. */
    static List<VitalRow> vitalRows(Vitals v) {
        List<VitalRow> rows = new ArrayList<>();
        if (v == null) {
            return rows;
        }
        add(rows, "8480-6", "Systolic blood pressure", v.bpSystolic(), "mm[Hg]");
        add(rows, "8462-4", "Diastolic blood pressure", v.bpDiastolic(), "mm[Hg]");
        add(rows, "8867-4", "Heart rate", v.pulse(), "/min");
        add(rows, "8310-5", "Body temperature", v.temperatureF(), "[degF]");
        add(rows, "59408-5", "Oxygen saturation (SpO2)", v.spo2(), "%");
        add(rows, "29463-7", "Body weight", v.weightKg(), "kg");
        add(rows, "8302-2", "Body height", v.heightCm(), "cm");
        add(rows, "9279-1", "Respiratory rate", v.respiratoryRate(), "/min");
        add(rows, "2339-0", "Glucose [Mass/volume] in Blood", v.bloodGlucoseMgDl(), "mg/dL");
        return rows;
    }

    private static void add(List<VitalRow> rows, String code, String display, Number value, String unit) {
        if (value != null) {
            rows.add(new VitalRow(code, display, new BigDecimal(value.toString()), unit));
        }
    }

    void replace(UUID encounterId, long profileId, long clinicId, Instant observedAt, EncounterContent c) {
        deleteAll(encounterId);

        jdbc.sql("""
                INSERT INTO encounter_note (encounter_id, chief_complaints, history_present_illness, past_history,
                                            examination, treatment_given, advice, private_note)
                VALUES (:id, :cc, :hpi, :past, :exam, :treatment, :advice, :private)
                """)
                .param("id", encounterId)
                .param("cc", c.chiefComplaints())
                .param("hpi", c.historyOfPresentIllness())
                .param("past", c.pastHistory())
                .param("exam", c.examination())
                .param("treatment", c.treatmentGiven())
                .param("advice", c.advice())
                .param("private", c.privateNote())
                .update();

        for (VitalRow v : vitalRows(c.vitals())) {
            jdbc.sql("""
                    INSERT INTO vital_sign (encounter_id, profile_id, code, display, value, unit, observed_at)
                    VALUES (:id, :profileId, :code, :display, :value, :unit, :observedAt)
                    """)
                    .param("id", encounterId)
                    .param("profileId", profileId)
                    .param("code", v.code())
                    .param("display", v.display())
                    .param("value", v.value())
                    .param("unit", v.unit())
                    .param("observedAt", java.sql.Timestamp.from(observedAt))
                    .update();
        }

        int line = 1;
        for (DiagnosisEntry d : c.diagnoses()) {
            jdbc.sql("""
                    INSERT INTO diagnosis (encounter_id, profile_id, line_no, code_system, code, display, kind, chronic)
                    VALUES (:id, :profileId, :line, :system, :code, :display, :kind, :chronic)
                    """)
                    .param("id", encounterId)
                    .param("profileId", profileId)
                    .param("line", line++)
                    .param("system", d.code() == null ? null : (d.codeSystem() == null ? "ICD10" : d.codeSystem()))
                    .param("code", d.code())
                    .param("display", d.display().trim())
                    .param("kind", d.kind() == null ? "PRIMARY" : d.kind())
                    .param("chronic", d.chronic())
                    .update();
        }

        if (!c.prescription().isEmpty()) {
            UUID rxId = jdbc.sql("""
                    INSERT INTO prescription (encounter_id, profile_id) VALUES (:id, :profileId) RETURNING id
                    """)
                    .param("id", encounterId)
                    .param("profileId", profileId)
                    .query(UUID.class)
                    .single();
            line = 1;
            for (RxItem r : c.prescription()) {
                jdbc.sql("""
                        INSERT INTO prescription_item (prescription_id, line_no, drug_id, drug_display, generic_name, dose,
                                                       frequency, route, timing, duration_days, long_term, instructions)
                        VALUES (:rxId, :line, :drugId, :drug, :generic, :dose, :freq, :route, :timing, :days, :longTerm, :instr)
                        """)
                        .param("rxId", rxId)
                        .param("line", line++)
                        .param("drugId", r.drugId())
                        .param("drug", r.drugDisplay().trim())
                        .param("generic", r.genericName())
                        .param("dose", r.dose())
                        .param("freq", r.frequency() == null || r.frequency().isBlank() ? null : r.frequency().trim())
                        .param("route", r.route())
                        .param("timing", r.timing())
                        .param("days", r.durationDays())
                        .param("longTerm", r.longTerm())
                        .param("instr", r.instructions())
                        .update();
            }
        }

        line = 1;
        for (InvestigationEntry i : c.investigations()) {
            jdbc.sql("""
                    INSERT INTO investigation_order (encounter_id, profile_id, line_no, code, display)
                    VALUES (:id, :profileId, :line, :code, :display)
                    """)
                    .param("id", encounterId)
                    .param("profileId", profileId)
                    .param("line", line++)
                    .param("code", i.code())
                    .param("display", i.display().trim())
                    .update();
        }

        if (c.followUp() != null) {
            jdbc.sql("""
                    INSERT INTO follow_up (encounter_id, profile_id, clinic_id, due_date, note)
                    VALUES (:id, :profileId, :clinicId, :due, :note)
                    """)
                    .param("id", encounterId)
                    .param("profileId", profileId)
                    .param("clinicId", clinicId)
                    .param("due", c.followUp().dueDate())
                    .param("note", c.followUp().note())
                    .update();
        }
    }

    private void deleteAll(UUID encounterId) {
        jdbc.sql("DELETE FROM prescription_item WHERE prescription_id IN (SELECT id FROM prescription WHERE encounter_id = :id)")
                .param("id", encounterId).update();
        for (String table : List.of("prescription", "vital_sign", "diagnosis", "investigation_order", "follow_up", "encounter_note")) {
            jdbc.sql("DELETE FROM " + table + " WHERE encounter_id = :id").param("id", encounterId).update();
        }
    }
}
