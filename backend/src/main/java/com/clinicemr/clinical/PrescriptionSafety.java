package com.clinicemr.clinical;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.clinicemr.clinical.EncounterContent.RxItem;

/**
 * Advisory checks shown to the doctor while prescribing. They never block signing; the doctor decides.
 * <p>
 * The allergy-class list is a small starter set, not a drug-interaction database. A licensed database is planned
 * for that (see the plan, Release 3).
 */
public final class PrescriptionSafety {

    private PrescriptionSafety() {
    }

    public record Warning(String type, String message, Integer line) {
    }

    /** Allergy terms (as patients and doctors write them) and the drug names they cover. */
    private static final Map<String, List<String>> ALLERGY_CLASSES = Map.of(
            "penicillin", List.of("penicillin", "amoxicillin", "ampicillin", "cloxacillin", "piperacillin", "benzathine"),
            "sulfa", List.of("sulfamethoxazole", "cotrimoxazole", "co-trimoxazole", "sulfasalazine", "sulfadiazine"),
            "sulpha", List.of("sulfamethoxazole", "cotrimoxazole", "co-trimoxazole", "sulfasalazine", "sulfadiazine"),
            "nsaid", List.of("ibuprofen", "diclofenac", "naproxen", "aceclofenac", "ketorolac", "mefenamic", "aspirin",
                    "piroxicam", "etoricoxib"),
            "cephalosporin", List.of("cefixime", "cefuroxime", "ceftriaxone", "cefpodoxime", "cefalexin", "cephalexin",
                    "cefadroxil"),
            "aspirin", List.of("aspirin", "acetylsalicylic"));

    public static List<Warning> check(EncounterContent content, List<String> allergySubstances) {
        List<Warning> warnings = new ArrayList<>();
        List<RxItem> items = content.prescription();

        Map<String, Set<String>> allergyKeywords = new HashMap<>();
        for (String substance : allergySubstances) {
            String s = substance.toLowerCase(Locale.ROOT).trim();
            Set<String> keywords = new LinkedHashSet<>();
            keywords.add(s);
            ALLERGY_CLASSES.forEach((term, drugs) -> {
                if (s.contains(term)) {
                    keywords.addAll(drugs);
                }
            });
            allergyKeywords.put(substance, keywords);
        }

        Map<String, Integer> firstLineByKey = new HashMap<>();
        for (int i = 0; i < items.size(); i++) {
            RxItem item = items.get(i);
            int line = i + 1;
            if (item.drugDisplay() == null || item.drugDisplay().isBlank()) {
                continue;
            }
            String text = (item.drugDisplay() + " " + (item.genericName() == null ? "" : item.genericName()))
                    .toLowerCase(Locale.ROOT);

            allergyKeywords.forEach((substance, keywords) -> {
                if (keywords.stream().anyMatch(text::contains)) {
                    warnings.add(new Warning("ALLERGY",
                            "Line " + line + " (" + item.drugDisplay() + ") may conflict with the recorded allergy: " + substance + ".",
                            line));
                }
            });

            String key = item.genericName() != null && !item.genericName().isBlank()
                    ? item.genericName().toLowerCase(Locale.ROOT).trim()
                    : item.drugDisplay().toLowerCase(Locale.ROOT).trim();
            Integer first = firstLineByKey.putIfAbsent(key, line);
            if (first != null) {
                warnings.add(new Warning("DUPLICATE_MEDICINE",
                        "Line " + line + " repeats a medicine already on line " + first + ".", line));
            }
        }
        return warnings;
    }
}
