package com.clinicemr.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.clinicemr.shared.PhoneNumbers;

/**
 * Sends messages only after the surrounding transaction commits, so a rolled-back request never texts the patient.
 * A failed send is logged and doesn't undo the committed change.
 * <p>
 * TODO: move to the outbox with retries once a real provider is connected.
 */
@Component
public class Notifier {

    private static final Logger log = LoggerFactory.getLogger(Notifier.class);

    private final SmsSender sms;

    public Notifier(SmsSender sms) {
        this.sms = sms;
    }

    public void smsAfterCommit(String phoneE164, String message) {
        Runnable send = () -> {
            try {
                sms.send(phoneE164, message);
            } catch (RuntimeException e) {
                log.error("SMS to {} failed", PhoneNumbers.mask(phoneE164), e);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send.run();
                }
            });
        } else {
            send.run();
        }
    }
}
