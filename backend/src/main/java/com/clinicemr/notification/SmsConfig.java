package com.clinicemr.notification;

import com.clinicemr.shared.PhoneNumbers;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Development stand-in: writes messages to the log instead of sending them, so OTPs can be read from the console.
 * Replaced automatically once a real {@link SmsSender} bean exists. Never run this in production: OTPs in logs
 * would let anyone with log access approve record sharing.
 */
@Configuration
class SmsConfig {

    @Bean
    @ConditionalOnMissingBean(SmsSender.class)
    SmsSender loggingSmsSender() {
        return new LoggingSmsSender();
    }

    static class LoggingSmsSender implements SmsSender {

        private static final Logger log = LoggerFactory.getLogger(LoggingSmsSender.class);

        @PostConstruct
        void warn() {
            log.warn("No SMS provider configured: messages (including OTPs) are written to this log. Development only.");
        }

        @Override
        public void send(String phoneE164, String message) {
            log.info("[DEV SMS to {}] {}", PhoneNumbers.mask(phoneE164), message);
        }
    }
}
