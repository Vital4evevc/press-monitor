package com.ourcrowd.pressmonitor.config;

import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * The HTTP client we use to call press-monitor-backend.
 */
@Configuration
public class AppConfig {

    @Bean
    public RestClient backendRestClient(BackendProperties props) {
        var settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofSeconds(props.getConnectTimeoutSeconds()))
                .withReadTimeout(Duration.ofSeconds(props.getReadTimeoutSeconds()));
        return RestClient.builder()
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .baseUrl(props.getBaseUrl())
                .build();
    }
}
