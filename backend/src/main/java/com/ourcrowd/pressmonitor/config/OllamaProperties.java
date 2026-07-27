package com.ourcrowd.pressmonitor.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for the locally hosted Ollama server (see ollama.* in application.yml).
 *
 * Everything application.yml sets is left without a default here on purpose. A value written
 * in two places drifts: change the yml, forget the field, and the two disagree with nothing to
 * tell you which one won. The yml is the single source, and a missing key fails at startup
 * rather than silently falling back to whatever a field initialiser happened to say.
 *
 * The boxed types exist for the same reason. A primitive int always has a value — 0 — so there
 * is no such thing as "not configured" for one, and a missing key would quietly mean zero
 * timeout. Wrappers can be null, which is what makes @NotNull able to catch it.
 */
@Validated
@ConfigurationProperties(prefix = "ollama")
public class OllamaProperties {

    @NotBlank
    private String baseUrl;

    @NotBlank
    private String model;

    @NotNull
    @Positive
    private Integer timeoutSeconds;

    // Range rather than @Positive: 0.0 is the value we actually want, since it makes
    // classification deterministic. Anything above 2.0 is nonsense for any Ollama model.
    @NotNull
    @DecimalMin("0.0")
    @DecimalMax("2.0")
    private Double temperature;

    /**
     * Whether the model should produce a reasoning block before its answer. Off by default:
     * our default model (qwen3) is a hybrid reasoning model, and its thinking output fights
     * with the JSON format constraint we rely on for classification.
     *
     * This one keeps its default, unlike everything above, because application.yml doesn't set
     * it — there's nowhere else for the value to come from. Null is meaningful too: it means
     * "don't send the flag at all", which is what you want for a model or an Ollama build that
     * doesn't understand it. OllamaClient also retries without the flag if a request carrying
     * it fails, so this rarely needs touching.
     */
    private Boolean think = Boolean.FALSE;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Integer getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(Integer timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public Boolean getThink() {
        return think;
    }

    public void setThink(Boolean think) {
        this.think = think;
    }
}
