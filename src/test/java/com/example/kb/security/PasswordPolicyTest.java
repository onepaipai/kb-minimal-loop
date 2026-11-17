package com.example.kb.security;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 密码策略：至少 10 位且同时含字母和数字。 */
class PasswordPolicyTest {

    @Test
    void validPasswordPasses() {
        assertDoesNotThrow(() -> PasswordPolicy.validate("Abcdef1234"));
    }

    @Test
    void tooShortFails() {
        assertThrows(ResponseStatusException.class, () -> PasswordPolicy.validate("Abc123"));
    }

    @Test
    void lettersOnlyFails() {
        assertThrows(ResponseStatusException.class, () -> PasswordPolicy.validate("abcdefghij"));
    }

    @Test
    void digitsOnlyFails() {
        assertThrows(ResponseStatusException.class, () -> PasswordPolicy.validate("1234567890"));
    }

    @Test
    void nullFails() {
        assertThrows(ResponseStatusException.class, () -> PasswordPolicy.validate(null));
    }
}
