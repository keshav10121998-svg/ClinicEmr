package com.clinicemr.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.clinicemr.shared.error.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PhoneNumbersTest {

    @ParameterizedTest
    @ValueSource(strings = {"9876543210", "98765 43210", "+91 98765-43210", "919876543210", "09876543210"})
    void normalisesIndianMobiles(String raw) {
        assertThat(PhoneNumbers.toE164(raw)).isEqualTo("+919876543210");
    }

    // Exactly 10 digits: fewer or more are refused, as are landlines and other countries
    @ParameterizedTest
    @ValueSource(strings = {"12345", "987654321", "98765432101", "987654321012", "1234567890", "5876543210",
            "+91 98765", "+44 20 7946 0958", "0120 4567890", "abc", " "})
    void rejectsInvalid(String raw) {
        assertThatThrownBy(() -> PhoneNumbers.toE164(raw)).isInstanceOf(ApiException.class);
    }

    @Test
    void masksAllButLastFour() {
        assertThat(PhoneNumbers.mask("+919876543210")).isEqualTo("******3210");
    }
}
