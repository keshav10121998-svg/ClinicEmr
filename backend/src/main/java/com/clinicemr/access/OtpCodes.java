package com.clinicemr.access;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.clinicemr.shared.security.SecurityProperties;
import org.springframework.stereotype.Component;

/** Six-digit one-time codes, stored only as an HMAC bound to the request they belong to. */
@Component
public class OtpCodes {

    public static final int MAX_ATTEMPTS = 5;

    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;

    public OtpCodes(SecurityProperties props) {
        this.key = new SecretKeySpec(("otp:" + props.jwtSecret()).getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    public String generate() {
        return String.format("%06d", random.nextInt(1_000_000));
    }

    /** {@code context} (request id or phone) stops a code hash being reused elsewhere. */
    public String hash(String code, String context) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal((context + ":" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    public boolean matches(String code, String context, String expectedHash) {
        if (code == null || !code.matches("\\d{6}")) {
            return false;
        }
        return MessageDigest.isEqual(hash(code, context).getBytes(StandardCharsets.UTF_8),
                expectedHash.getBytes(StandardCharsets.UTF_8));
    }
}
