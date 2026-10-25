package com.clinicemr.shared.web;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Opening a PDF in a new tab needs a real address: phone browsers can't show a PDF built in page memory (a blob),
 * and links can't carry the sign-in header. So a signed-in request makes the PDF and parks it here under a random,
 * unguessable link that works for a few minutes; the browser then simply opens that link.
 * Kept in memory: fine for a single server, and nothing outlives a restart.
 */
@Component
@RestController
public class PdfLinks {

    static final String PATH = "/api/v1/pdf/";
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final int MAX_PARKED = 500;

    private record Parked(String filename, byte[] bytes, Instant expiresAt) {
    }

    private final Map<String, Parked> parked = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public record Link(String url, Instant expiresAt) {
    }

    /** Parks a PDF and returns the relative link the browser opens. */
    public Link park(String filename, byte[] pdf) {
        Instant now = Instant.now();
        parked.values().removeIf(p -> p.expiresAt().isBefore(now));
        if (parked.size() >= MAX_PARKED) {
            parked.clear();
        }
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Instant expiresAt = now.plus(TTL);
        parked.put(token, new Parked(filename, pdf, expiresAt));
        return new Link(PATH + token, expiresAt);
    }

    @GetMapping(PATH + "{token}")
    public ResponseEntity<byte[]> open(@PathVariable String token) {
        Optional<Parked> p = Optional.ofNullable(parked.get(token)).filter(x -> x.expiresAt().isAfter(Instant.now()));
        if (p.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(p.get().filename()).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .header("Referrer-Policy", "no-referrer")
                .body(p.get().bytes());
    }
}
