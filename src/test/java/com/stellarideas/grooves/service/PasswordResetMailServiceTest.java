package com.stellarideas.grooves.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PasswordResetMailServiceTest {

    @Mock
    private JavaMailSender mailSender;

    private PasswordResetMailService service;

    @BeforeEach
    void setUp() {
        service = new PasswordResetMailService(mailSender);
        ReflectionTestUtils.setField(service, "fromAddress", "noreply@stellargrooves.local");
        ReflectionTestUtils.setField(service, "baseUrl", "http://localhost:8080");
    }

    @Test
    void sendResetEmail_whenDisabled_doesNotSend() {
        ReflectionTestUtils.setField(service, "mailEnabled", false);

        service.sendResetEmail("user@example.com", "testuser", "reset-token-123");

        verifyNoInteractions(mailSender);
    }

    @Test
    void sendResetEmail_whenEnabled_sendsCorrectMessage() {
        ReflectionTestUtils.setField(service, "mailEnabled", true);

        service.sendResetEmail("user@example.com", "testuser", "reset-token-123");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender, times(1)).send(captor.capture());

        SimpleMailMessage sent = captor.getValue();
        assertEquals("noreply@stellargrooves.local", sent.getFrom());
        assertArrayEquals(new String[]{"user@example.com"}, sent.getTo());
        assertEquals("Stellar Grooves \u2014 Password Reset", sent.getSubject());
        assertNotNull(sent.getText());
        assertTrue(sent.getText().contains("testuser"));
        assertTrue(sent.getText().contains("http://localhost:8080/password-reset?token=reset-token-123"));
    }

    @Test
    void sendResetEmail_whenMailSenderThrows_doesNotPropagate() {
        ReflectionTestUtils.setField(service, "mailEnabled", true);
        doThrow(new MailSendException("SMTP down")).when(mailSender).send(any(SimpleMailMessage.class));

        assertDoesNotThrow(() ->
                service.sendResetEmail("user@example.com", "testuser", "reset-token-123"));

        verify(mailSender, times(1)).send(any(SimpleMailMessage.class));
    }
}
