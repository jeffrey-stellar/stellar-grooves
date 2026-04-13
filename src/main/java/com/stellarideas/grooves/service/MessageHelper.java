package com.stellarideas.grooves.service;

import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class MessageHelper {

    private final MessageSource messageSource;

    public MessageHelper(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    public String msg(String code, Object... args) {
        return messageSource.getMessage(code, args, Locale.getDefault());
    }
}
