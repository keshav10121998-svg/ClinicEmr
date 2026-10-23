package com.clinicemr.records;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Checks that a file's first bytes match the type it claims, so a renamed executable or script can't be stored
 * as a "PDF". Not a virus scan; ClamAV scanning is still planned.
 */
final class FileSignatures {

    private FileSignatures() {
    }

    static boolean matches(String mimeType, byte[] b) {
        return switch (mimeType) {
            case "application/pdf" -> startsWith(b, 0, "%PDF-".getBytes(StandardCharsets.US_ASCII));
            case "image/jpeg" -> startsWith(b, 0, new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
            case "image/png" -> startsWith(b, 0, new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
            case "image/webp" -> startsWith(b, 0, "RIFF".getBytes(StandardCharsets.US_ASCII))
                    && startsWith(b, 8, "WEBP".getBytes(StandardCharsets.US_ASCII));
            // ISO base media file: "ftyp" box at offset 4 with a HEIF brand
            case "image/heic" -> startsWith(b, 4, "ftyp".getBytes(StandardCharsets.US_ASCII))
                    && b.length >= 12
                    && Arrays.asList("heic", "heix", "hevc", "hevx", "mif1", "msf1")
                            .contains(new String(b, 8, 4, StandardCharsets.US_ASCII));
            default -> false;
        };
    }

    private static boolean startsWith(byte[] b, int offset, byte[] prefix) {
        if (b.length < offset + prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (b[offset + i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
