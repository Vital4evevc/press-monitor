package com.ourcrowd.pressmonitor.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for alert delivery (see alert.* in application.yml). Alerts go out exclusively
 * through a webhook — see AlertService for the actual delivery logic.
 */
@Validated
@Component
@ConfigurationProperties(prefix = "alert")
public class AlertProperties {

    /**
     * Where new-coverage alerts get POSTed.
     *
     * Deliberately has no default, unlike the other properties classes here. This is an
     * environment-specific destination that receives company names and headlines, so quietly
     * falling back to something when it isn't configured is the wrong behaviour — especially
     * since the obvious fallback is a webhook.site inbox that anyone holding the URL can read.
     * Refusing to start and saying why is safer than sending real data somewhere public.
     *
     * The default that keeps the demo working out of the box lives in application.yml instead,
     * where it's visible and greppable rather than buried in a field initialiser several files
     * away from anything that reads it.
     */
    @NotBlank(message = "alert.webhook-url must be set (environment variable ALERT_WEBHOOK_URL)")
    private String webhookUrl;

    public String getWebhookUrl() {
        return webhookUrl;
    }

    public void setWebhookUrl(String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }
}
