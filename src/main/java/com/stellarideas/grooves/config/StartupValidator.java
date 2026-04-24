package com.stellarideas.grooves.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

@Component
public class StartupValidator {

    private static final Logger log = LoggerFactory.getLogger(StartupValidator.class);
    private static final String PROD_PROFILE = "prod";

    private final Environment environment;
    private final String allowedOrigins;

    public StartupValidator(Environment environment,
                            @Value("${stellar.grooves.cors.allowedOrigins:}") String allowedOrigins) {
        this.environment = environment;
        this.allowedOrigins = allowedOrigins;
    }

    @PostConstruct
    void validate() {
        List<String> profiles = Arrays.asList(environment.getActiveProfiles());
        boolean prod = profiles.contains(PROD_PROFILE);
        boolean corsEmpty = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .allMatch(String::isEmpty);

        if (prod && corsEmpty) {
            throw new IllegalStateException(
                    "CORS_ALLOWED_ORIGINS must be set when running with the 'prod' profile. "
                    + "Provide a comma-separated list of allowed origins, e.g. "
                    + "CORS_ALLOWED_ORIGINS=https://grooves.example.com");
        }
        if (corsEmpty) {
            log.warn("stellar.grooves.cors.allowedOrigins is empty; cross-origin requests will be rejected.");
        } else {
            log.info("CORS allowed origins: {}", allowedOrigins);
        }
        log.info("Active Spring profiles: {}", profiles.isEmpty() ? "(default)" : profiles);
    }
}
