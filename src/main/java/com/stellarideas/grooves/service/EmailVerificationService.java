package com.stellarideas.grooves.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class EmailVerificationService {

    private static final Logger logger = LoggerFactory.getLogger(EmailVerificationService.class);

    private final JavaMailSender mailSender;

    @Value("${stellar.grooves.mail.from:noreply@stellargrooves.local}")
    private String fromAddress;

    @Value("${stellar.grooves.mail.enabled:false}")
    private boolean mailEnabled;

    @Value("${stellar.grooves.baseUrl:http://localhost:8080}")
    private String baseUrl;

    public EmailVerificationService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    public void sendVerificationEmail(String toEmail, String username, String token) {
        if (!mailEnabled) {
            logger.info("Mail disabled — email verification requested for user '{}'. Token: {}", username, token);
            return;
        }

        String verifyUrl = baseUrl + "/api/v1/auth/verify-email?token=" + token;

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(toEmail);
        message.setSubject("Stellar Grooves — Verify Your Email");
        message.setText("Hi " + username + ",\n\n"
                + "Welcome to Stellar Grooves! Please verify your email address by clicking the link below:\n\n"
                + verifyUrl + "\n\n"
                + "This link expires in 24 hours.\n\n"
                + "If you did not create this account, you can safely ignore this email.\n\n"
                + "— Stellar Grooves");

        try {
            mailSender.send(message);
            logger.info("Verification email sent to user '{}'", username);
        } catch (Exception e) {
            logger.error("Failed to send verification email to user '{}': {}", username, e.getMessage(), e);
        }
    }
}
