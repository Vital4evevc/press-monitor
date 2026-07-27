package com.ourcrowd.pressmonitor.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Our shared HTTP clients.
 *
 * We keep two purpose-built RestClient beans: one for talking to the local Ollama server,
 * which needs a long read timeout for model inference, and one for outbound alert webhooks
 * and RSS fetching, which can stay on a short timeout.
 */
@Configuration
public class AppConfig {

    // Tuned for Ollama inference calls — needs a generous read timeout.
    @Bean("ollamaRestClient")
    public RestClient ollamaRestClient(OllamaProperties props) {
        var settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofSeconds(5))
                .withReadTimeout(Duration.ofSeconds(props.getTimeoutSeconds()));
        return RestClient.builder()
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .baseUrl(props.getBaseUrl())
                .build();
    }

    // General-purpose client for short outbound calls, like webhooks and RSS.
    @Bean("httpRestClient")
    public RestClient httpRestClient() {
        var settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofSeconds(10))
                .withReadTimeout(Duration.ofSeconds(20));
        return RestClient.builder()
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .build();
    }

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .findAndRegisterModules()
                // write Instant/LocalDate as ISO-8601 strings instead of numeric timestamps
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
