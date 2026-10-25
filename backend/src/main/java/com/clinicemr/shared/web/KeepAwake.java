package com.clinicemr.shared.web;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Keeps a free host awake: Render's free plan stops an app after 15 minutes without visitors, and the next visitor
 * then waits about a minute. Every 14 minutes this visits the app's own public health address, which counts as a
 * visitor (it goes out and back in through Render) and also touches the database, so Supabase's free project
 * doesn't pause either. On with {@code KEEP_AWAKE=true}; off locally.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "app.keep-awake", name = "enabled", havingValue = "true")
public class KeepAwake {

    private static final Logger log = LoggerFactory.getLogger(KeepAwake.class);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    private final URI target;

    public KeepAwake(@Value("${app.storage.local-base-url}") String publicBaseUrl,
                     @Value("${app.keep-awake.interval:PT14M}") Duration interval) {
        this.target = URI.create(publicBaseUrl.replaceAll("/+$", "") + "/actuator/health");
        log.info("Keep-awake on: visiting {} every {} minutes", target, interval.toMinutes());
    }

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "${app.keep-awake.interval:PT14M}")
    void ping() {
        try {
            HttpResponse<Void> res = http.send(HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(30)).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            if (res.statusCode() != 200) {
                log.warn("Keep-awake visit to {} answered {}", target, res.statusCode());
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Keep-awake visit to {} failed: {}", target, e.toString());
        }
    }
}
