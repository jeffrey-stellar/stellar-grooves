package com.stellarideas.grooves.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Configuration
@EnableMethodSecurity
public class WebSecurityConfig {

    private static final Logger logger = LoggerFactory.getLogger(WebSecurityConfig.class);

    private final UserDetailsServiceImpl userDetailsService;
    private final JwtUtils jwtUtils;

    @Value("${stellar.grooves.cors.allowedOrigins:http://localhost:8089,http://127.0.0.1:8089}")
    private String allowedOrigins;

    @Value("${stellar.grooves.swagger.enabled:false}")
    private boolean swaggerEnabled;

    public WebSecurityConfig(UserDetailsServiceImpl userDetailsService, JwtUtils jwtUtils) {
        this.userDetailsService = userDetailsService;
        this.jwtUtils = jwtUtils;
    }

    @Bean
    public AuthTokenFilter authenticationJwtTokenFilter() {
        return new AuthTokenFilter(jwtUtils, userDetailsService);
    }

    @Bean
    public DaoAuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider();
        authProvider.setUserDetailsService(userDetailsService);
        authProvider.setPasswordEncoder(passwordEncoder());
        return authProvider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authConfig) throws Exception {
        return authConfig.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
        if (origins.isEmpty()) {
            throw new IllegalStateException(
                    "CORS allowed origins not configured. Set CORS_ALLOWED_ORIGINS "
                    + "(comma-separated list of origins, e.g. https://grooves.example.com).");
        }
        validateOrigins(origins);
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-XSRF-TOKEN", "X-CSRF-TOKEN"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    /**
     * Reject origins that are clearly misconfigured. {@code allowCredentials=true}
     * makes wildcard origins both unsafe and silently rejected by browsers, so we
     * fail fast rather than ship a broken CORS policy.
     */
    static void validateOrigins(List<String> origins) {
        for (String origin : origins) {
            if ("*".equals(origin) || "null".equalsIgnoreCase(origin)) {
                throw new IllegalStateException(
                        "CORS allowed origins must not include '" + origin + "'. "
                        + "List specific origins (e.g. https://grooves.example.com).");
            }
            if (!origin.startsWith("http://") && !origin.startsWith("https://")) {
                throw new IllegalStateException(
                        "CORS origin '" + origin + "' must include a scheme (http:// or https://).");
            }
            if (origin.endsWith("/")) {
                throw new IllegalStateException(
                        "CORS origin '" + origin + "' must not have a trailing slash.");
            }
            if (origin.startsWith("http://") && !origin.startsWith("http://localhost")
                    && !origin.startsWith("http://127.0.0.1") && !origin.startsWith("http://[::1]")) {
                logger.warn("CORS origin '{}' uses plain http:// — prefer https:// outside local development.", origin);
            }
        }
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> {
                CsrfTokenRequestAttributeHandler requestHandler = new CsrfTokenRequestAttributeHandler();
                requestHandler.setCsrfRequestAttributeName(null); // opt out of deferred loading so the token is always available
                csrf
                    .csrfTokenRepository(new CookieCsrfTokenRepository()) // HttpOnly cookie; JS reads token from meta tags
                    .csrfTokenRequestHandler(requestHandler)
                    .ignoringRequestMatchers("/api/v1/auth/**", "/api/v1/shared/**", "/ws/**"); // stateless JWT auth endpoints, public shared endpoints, and WebSocket handshake don't need CSRF
            })
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
            .headers(headers -> {
                headers
                    .frameOptions(frame -> frame.deny())
                    .contentTypeOptions(cto -> {})
                    .referrerPolicy(rp -> rp.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                    .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000));
                headers.permissionsPolicy(pp -> pp.policy(
                    "geolocation=(), microphone=(), camera=(), payment=(), usb=()"));
                // Note: style-src 'unsafe-inline' is required by Bootstrap's dynamic inline styles (modals, tooltips).
                // All vendor assets (Bootstrap, SockJS, STOMP) are served locally — no CDN origins needed.
                headers.contentSecurityPolicy(csp -> csp.policyDirectives(
                    "default-src 'self'; "
                    + "script-src 'self'; "
                    + "style-src 'self' 'unsafe-inline'; "
                    + "font-src 'self'; "
                    + "img-src 'self' data:; "
                    + "connect-src 'self'; "
                    + "object-src 'none'; "
                    + "base-uri 'self'; "
                    + "form-action 'self'"
                ));
            })
            .authorizeHttpRequests(auth -> {
                auth.requestMatchers("/api/v1/auth/**").permitAll()
                    .requestMatchers("/api/v1/shared/**").permitAll()
                    .requestMatchers("/ws/**").permitAll()
                    .requestMatchers("/login", "/signup", "/help", "/shared/**", "/css/**", "/js/**", "/vendor/**", "/fonts/**", "/images/**", "/favicon.ico", "/manifest.json", "/sw.js", "/offline.html", "/actuator/health", "/actuator/metrics/**", "/actuator/prometheus").permitAll();
                if (swaggerEnabled) {
                    auth.requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/api-docs/**", "/v3/api-docs/**").permitAll();
                } else {
                    auth.requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/api-docs/**", "/v3/api-docs/**").denyAll();
                }
                auth.anyRequest().authenticated();
            })
            .formLogin(form -> form
                .loginPage("/login")
                .defaultSuccessUrl("/", true)
                .permitAll()
            )
            .logout(logout -> logout
                .logoutSuccessUrl("/login?logout")
                .permitAll());

        http.authenticationProvider(authenticationProvider());
        http.addFilterBefore(authenticationJwtTokenFilter(), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
