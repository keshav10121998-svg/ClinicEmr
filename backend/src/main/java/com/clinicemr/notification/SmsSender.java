package com.clinicemr.notification;

/**
 * Sends a text message. In production this is a TRAI DLT-registered SMS provider (and WhatsApp later); each message
 * text must match a registered template.
 */
public interface SmsSender {

    void send(String phoneE164, String message);
}
