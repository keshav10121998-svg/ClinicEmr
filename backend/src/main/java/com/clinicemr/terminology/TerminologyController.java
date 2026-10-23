package com.clinicemr.terminology;

import java.util.List;

import com.clinicemr.shared.error.ApiException;
import com.clinicemr.shared.security.ClinicContextResolver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Type-ahead search for diagnoses (ICD-10) and drugs. Matches code prefixes, words anywhere, local synonyms
 * ("sugar" → diabetes) and near-misspellings via trigram similarity.
 */
@RestController
@RequestMapping("/api/v1/terminology")
public class TerminologyController {

    private final JdbcClient jdbc;

    public TerminologyController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record DiagnosisOption(String codeSystem, String code, String display) {
    }

    public record DrugOption(long id, String genericName, String brandName, String strength, String form, String display) {
    }

    @GetMapping("/diagnoses")
    public List<DiagnosisOption> diagnoses(@RequestParam("q") String q) {
        ClinicContextResolver.currentStaffId();
        String term = term(q);
        return jdbc.sql("""
                SELECT 'ICD10' AS code_system, code, display
                  FROM icd10_code
                 WHERE code ILIKE :term || '%'
                    OR (display || ' ' || synonyms) ILIKE '%' || :term || '%'
                    OR similarity(display || ' ' || synonyms, :term) > 0.2
                 ORDER BY (code ILIKE :term || '%') DESC,
                          (display ILIKE :term || '%') DESC,
                          similarity(display || ' ' || synonyms, :term) DESC
                 LIMIT 15
                """)
                .param("term", term)
                .query(DiagnosisOption.class)
                .list();
    }

    @GetMapping("/drugs")
    public List<DrugOption> drugs(@RequestParam("q") String q) {
        ClinicContextResolver.currentStaffId();
        String term = term(q);
        return jdbc.sql("""
                SELECT id, generic_name, brand_name, strength, form,
                       coalesce(brand_name || ' (' || generic_name || ')', generic_name)
                         || coalesce(' ' || strength, '') || ' ' || form AS display
                  FROM drug
                 WHERE active
                   AND ((coalesce(brand_name, '') || ' ' || generic_name) ILIKE '%' || :term || '%'
                        OR similarity(coalesce(brand_name, '') || ' ' || generic_name, :term) > 0.25)
                 ORDER BY (generic_name ILIKE :term || '%' OR brand_name ILIKE :term || '%') DESC,
                          similarity(coalesce(brand_name, '') || ' ' || generic_name, :term) DESC,
                          generic_name, strength
                 LIMIT 15
                """)
                .param("term", term)
                .query(DrugOption.class)
                .list();
    }

    /** The whole starter list, for simple pick-as-you-type boxes that don't need a search request per keystroke. */
    @GetMapping("/diagnoses/all")
    public List<DiagnosisOption> allDiagnoses() {
        ClinicContextResolver.currentStaffId();
        return jdbc.sql("SELECT 'ICD10' AS code_system, code, display FROM icd10_code ORDER BY display LIMIT 2000")
                .query(DiagnosisOption.class)
                .list();
    }

    @GetMapping("/drugs/all")
    public List<DrugOption> allDrugs() {
        ClinicContextResolver.currentStaffId();
        return jdbc.sql("""
                SELECT id, generic_name, brand_name, strength, form,
                       coalesce(brand_name || ' (' || generic_name || ')', generic_name)
                         || coalesce(' ' || strength, '') || ' ' || form AS display
                  FROM drug WHERE active ORDER BY generic_name, strength LIMIT 2000
                """)
                .query(DrugOption.class)
                .list();
    }

    private static String term(String q) {
        String t = q == null ? "" : q.trim();
        if (t.length() < 2) {
            throw ApiException.badRequest("QUERY_TOO_SHORT", "Type at least 2 characters.");
        }
        if (t.length() > 60) {
            t = t.substring(0, 60);
        }
        // ILIKE wildcards typed by the user are treated as plain text
        return t.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
