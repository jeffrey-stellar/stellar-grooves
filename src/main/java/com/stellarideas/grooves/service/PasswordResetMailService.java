package com.stellarideas.grooves.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class PasswordResetMailService {

    private static final Logger logger = LoggerFactory.getLogger(PasswordResetMailService.class);

    private final JavaMailSender mailSender;

    @Value("${stellar.grooves.mail.from:noreply@stellargrooves.local}")
    private String fromAddress;

    @Value("${stellar.grooves.mail.enabled:false}")
    private boolean mailEnabled;

    @Value("${stellar.grooves.baseUrl:http://localhost:8089}")
    private String baseUrl;

    public PasswordResetMailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    public void sendResetEmail(String toEmail, String username, String token) {
        if (!mailEnabled) {
            logger.info("Mail disabled — password reset requested for user '{}'", username);
            return;
        }

        String resetUrl = baseUrl + "/password-reset?token=" + token;

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(toEmail);
        message.setSubject("Stellar Grooves — Password Reset");
        message.setText("Hi " + username + ",\n\n"
                + "A password reset was requested for your account.\n\n"
                + "Click the link below to reset your password (expires in 15 minutes):\n"
                + resetUrl + "\n\n"
                + "If you did not request this, you can safely ignore this email.\n\n"
                + "— Stellar Grooves");

        try {
            mailSender.send(message);
            logger.info("Password reset email sent to user '{}'", username);
        } catch (Exception e) {
            logger.error("Failed to send password reset email to user '{}': {}", username, e.getMessage(), e);
        }
    }
}
