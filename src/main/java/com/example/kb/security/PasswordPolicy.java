package com.example.kb.security;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 密码策略：至少 10 位，且含字母和数字；创建用户与改密码共用。 */
public final class PasswordPolicy {

    private static final int MIN_LENGTH = 10;

    private PasswordPolicy() {
    }

    public static void validate(String password) {
        if (password == null || password.length() < MIN_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "密码至少 10 位");
        }
        boolean hasLetter = false;
        boolean hasDigit = false;
        for (int i = 0; i < password.length(); i++) {
            char c = password.charAt(i);
            if (Character.isLetter(c)) {
                hasLetter = true;
            } else if (Character.isDigit(c)) {
                hasDigit = true;
            }
        }
        if (!hasLetter || !hasDigit) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "密码需同时包含字母和数字");
        }
    }
}
