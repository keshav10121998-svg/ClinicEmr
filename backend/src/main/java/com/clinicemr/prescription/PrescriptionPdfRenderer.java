package com.clinicemr.prescription;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.clinicemr.clinical.EncounterContent;
import com.clinicemr.clinical.EncounterContent.RxItem;
import com.clinicemr.clinical.EncounterContent.Vitals;
import com.clinicemr.clinical.SignedSnapshot;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.springframework.stereotype.Component;

/**
 * Renders a signed visit as a printable prescription. Built only from the signed snapshot, so a reprint always
 * matches what the doctor signed. The doctor's private note is never printed.
 */
@Component
public class PrescriptionPdfRenderer {

    public enum Paper { A4, A5 }

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH);

    public byte[] render(SignedSnapshot s, Paper paper) {
        String html = html(s, paper);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(html, null);
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Prescription PDF could not be rendered", e);
        }
    }

    String html(SignedSnapshot s, Paper paper) {
        EncounterContent c = s.content();
        StringBuilder h = new StringBuilder(4096);
        h.append("<html><head><meta charset=\"UTF-8\"/><title>Prescription</title><style>")
                .append("@page { size: ").append(paper.name()).append("; margin: 12mm 12mm 14mm 12mm; }")
                .append("""
                        body { font-family: sans-serif; font-size: 9.5pt; color: #111; }
                        .top { width: 100%; border-bottom: 1.5px solid #0d6b5a; padding-bottom: 6px; }
                        .top td { vertical-align: top; }
                        .clinic { font-size: 14pt; font-weight: bold; color: #0d6b5a; }
                        .muted { color: #555; font-size: 8.5pt; }
                        .doctor { text-align: right; }
                        .doctor b { font-size: 11pt; }
                        .pt { width: 100%; margin: 8px 0 6px; border-bottom: 1px solid #ccc; padding-bottom: 6px; }
                        h3 { font-size: 9pt; text-transform: uppercase; letter-spacing: 0.06em; color: #0d6b5a; margin: 9px 0 3px; }
                        p { margin: 0 0 3px; }
                        .rx { width: 100%; border-collapse: collapse; margin-top: 2px; }
                        .rx th { text-align: left; font-size: 8pt; color: #555; border-bottom: 1px solid #999; padding: 3px 4px; }
                        .rx td { border-bottom: 1px solid #e3e3e3; padding: 4px; vertical-align: top; }
                        .rx .drug { font-weight: bold; }
                        .rx .note { font-size: 8pt; color: #444; }
                        .sign { margin-top: 22px; text-align: right; }
                        .amended { margin-top: 10px; padding: 5px 7px; border: 1px solid #b4540f; color: #7a3a0b; font-size: 8.5pt; }
                        .foot { margin-top: 14px; font-size: 7.5pt; color: #777; border-top: 1px solid #ddd; padding-top: 4px; }
                        """)
                .append("</style></head><body>");

        // Letterhead: clinic on the left, doctor on the right
        SignedSnapshot.ClinicInfo clinic = s.clinic();
        SignedSnapshot.DoctorInfo doc = s.doctor();
        h.append("<table class=\"top\"><tr><td>")
                .append("<div class=\"clinic\">").append(esc(clinic.name())).append("</div>")
                .append("<div class=\"muted\">").append(esc(join(", ", clinic.address(), clinic.city(), clinic.state(), clinic.pincode()))).append("</div>");
        if (clinic.phone() != null) {
            h.append("<div class=\"muted\">Phone: ").append(esc(clinic.phone())).append("</div>");
        }
        h.append("</td><td class=\"doctor\">")
                .append("<b>").append(esc(doctorName(doc.name()))).append("</b><br/>")
                .append("<span class=\"muted\">").append(esc(doc.qualifications())).append("</span><br/>");
        if (doc.specialty() != null) {
            h.append("<span class=\"muted\">").append(esc(doc.specialty())).append("</span><br/>");
        }
        h.append("<span class=\"muted\">Reg. No. ").append(esc(doc.registrationNo()))
                .append(" (").append(esc(doc.registrationCouncil())).append(")</span>")
                .append("</td></tr></table>");

        // Patient line
        SignedSnapshot.PatientInfo p = s.patient();
        String age = p.ageYears() == null ? "" : p.ageYears() + (p.dobEstimated() ? " yrs (approx.)" : " yrs");
        h.append("<table class=\"pt\"><tr><td><b>").append(esc(p.name())).append("</b>")
                .append(" &#160; ").append(esc(join(" / ", age, genderLabel(p.gender()))))
                .append(" &#160; <span class=\"muted\">ID ").append(esc(p.mrn())).append("</span></td>")
                .append("<td style=\"text-align:right\">").append(esc(DATE_TIME.format(s.startedAt().atZone(IST)))).append("</td></tr></table>");

        String vitals = vitalsLine(c.vitals());
        if (!vitals.isEmpty()) {
            h.append("<p class=\"muted\">").append(esc(vitals)).append("</p>");
        }
        section(h, "Complaints", c.chiefComplaints());
        section(h, "History", c.historyOfPresentIllness());
        section(h, "Examination", c.examination());

        if (!c.diagnoses().isEmpty()) {
            h.append("<h3>Diagnosis</h3>");
            int i = 1;
            for (var d : c.diagnoses()) {
                h.append("<p>").append(i++).append(". ").append(esc(d.display()));
                if (d.code() != null) {
                    h.append(" <span class=\"muted\">(").append(esc(d.code())).append(")</span>");
                }
                if ("PROVISIONAL".equals(d.kind())) {
                    h.append(" <span class=\"muted\">provisional</span>");
                }
                h.append("</p>");
            }
        }

        section(h, "Treatment given", c.treatmentGiven());

        if (!c.prescription().isEmpty()) {
            h.append("<h3>Rx</h3><table class=\"rx\"><tr><th>#</th><th>Medicine</th><th>Dose</th><th>Frequency</th>")
                    .append("<th>When</th><th>Duration</th></tr>");
            int i = 1;
            for (RxItem r : c.prescription()) {
                h.append("<tr><td>").append(i++).append("</td><td><span class=\"drug\">").append(esc(r.drugDisplay())).append("</span>");
                if (r.genericName() != null && !r.drugDisplay().toLowerCase(Locale.ROOT).contains(r.genericName().toLowerCase(Locale.ROOT))) {
                    h.append("<br/><span class=\"note\">").append(esc(r.genericName())).append("</span>");
                }
                if (r.instructions() != null) {
                    h.append("<br/><span class=\"note\">").append(esc(r.instructions())).append("</span>");
                }
                h.append("</td><td>").append(esc(nz(r.dose())))
                        .append("</td><td>").append(esc(nz(r.frequency())))
                        .append("</td><td>").append(esc(nz(r.timing())))
                        .append("</td><td>").append(esc(durationLabel(r)))
                        .append("</td></tr>");
            }
            h.append("</table>");
        }

        if (!c.investigations().isEmpty()) {
            List<String> tests = new ArrayList<>();
            c.investigations().forEach(t -> tests.add(t.display()));
            section(h, "Investigations", String.join(", ", tests));
        }
        section(h, "Advice", c.advice());
        if (c.followUp() != null && c.followUp().dueDate() != null) {
            String fu = DATE.format(c.followUp().dueDate()) + (c.followUp().note() == null ? "" : " - " + c.followUp().note());
            section(h, "Follow-up", fu);
        }

        h.append("<div class=\"sign\"><b>").append(esc(doctorName(doc.name()))).append("</b><br/>")
                .append("<span class=\"muted\">Reg. No. ").append(esc(doc.registrationNo())).append("</span><br/>")
                .append("<span class=\"muted\">Signed electronically on ").append(esc(DATE_TIME.format(s.signedAt().atZone(IST))))
                .append("</span></div>");

        if (s.version() > 1) {
            h.append("<div class=\"amended\">Amended (version ").append(s.version()).append(") on ")
                    .append(esc(DATE_TIME.format(s.signedAt().atZone(IST)))).append(". Reason: ")
                    .append(esc(nz(s.amendmentReason()))).append(". This copy replaces earlier printouts of this visit.</div>");
        }

        h.append("<div class=\"foot\">Visit ").append(esc(s.encounterId().toString()))
                .append(" &#183; version ").append(s.version()).append("</div>")
                .append("</body></html>");
        return h.toString();
    }

    private static void section(StringBuilder h, String title, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        h.append("<h3>").append(esc(title)).append("</h3><p>")
                .append(esc(text.trim()).replace("\n", "<br/>"))
                .append("</p>");
    }

    static String vitalsLine(Vitals v) {
        if (v == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        if (v.bpSystolic() != null && v.bpDiastolic() != null) {
            parts.add("BP " + v.bpSystolic() + "/" + v.bpDiastolic() + " mmHg");
        }
        if (v.pulse() != null) parts.add("Pulse " + v.pulse() + "/min");
        if (v.temperatureF() != null) parts.add("Temp " + plain(v.temperatureF()) + " °F");
        if (v.spo2() != null) parts.add("SpO2 " + v.spo2() + "%");
        if (v.respiratoryRate() != null) parts.add("RR " + v.respiratoryRate() + "/min");
        if (v.weightKg() != null) parts.add("Wt " + plain(v.weightKg()) + " kg");
        if (v.heightCm() != null) parts.add("Ht " + plain(v.heightCm()) + " cm");
        if (v.bloodGlucoseMgDl() != null) parts.add("RBS " + plain(v.bloodGlucoseMgDl()) + " mg/dL");
        return String.join("  ·  ", parts);
    }

    static String durationLabel(RxItem r) {
        if (r.longTerm()) {
            return "Continue";
        }
        if (r.durationDays() == null) {
            return "";
        }
        return r.durationDays() + (r.durationDays() == 1 ? " day" : " days");
    }

    private static String plain(BigDecimal d) {
        return d.stripTrailingZeros().toPlainString();
    }

    private static String doctorName(String name) {
        return name.matches("(?i)^dr\\.?\\s.*") ? name : "Dr. " + name;
    }

    private static String genderLabel(String g) {
        return switch (g) {
            case "M" -> "Male";
            case "F" -> "Female";
            case "O" -> "Other";
            default -> "";
        };
    }

    private static String join(String sep, String... parts) {
        List<String> out = new ArrayList<>();
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                out.add(part.trim());
            }
        }
        return String.join(sep, out);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** XML escaping; the renderer needs well-formed XHTML. Control characters are dropped. */
    static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> {
                    if (ch >= 0x20 || ch == '\n' || ch == '\t') {
                        out.append(ch);
                    }
                }
            }
        }
        return out.toString();
    }
}
