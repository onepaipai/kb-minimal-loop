package com.example.kb.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** OIDC 用户名派生：邮箱本地部分做用户名，只留合法字符；没有邮箱就用 subject 哈希拼一个。 */
class OidcUsernameTest {

    @Test
    void emailLocalPartBecomesUsername() {
        assertEquals("zhang_san", OidcLoginSuccessHandler.deriveUsername("zhang.san@company.com", "sub-1"));
    }

    @Test
    void illegalCharsAreReplaced() {
        assertEquals("zhang_san", OidcLoginSuccessHandler.deriveUsername("zhang+san@company.com", "sub-1"));
    }

    @Test
    void noEmailUsesSubjectHash() {
        String username = OidcLoginSuccessHandler.deriveUsername(null, "subject-123");
        assertTrue(username.startsWith("oidc-"), username);
        assertTrue(username.length() >= 3 && username.length() <= 32);
    }

    @Test
    void shortLocalPartIsPadded() {
        String username = OidcLoginSuccessHandler.deriveUsername("a@company.com", "sub-1");
        assertTrue(username.length() >= 3, username);
    }

    @Test
    void longLocalPartIsTruncated() {
        String username = OidcLoginSuccessHandler.deriveUsername("averyveryveryverylonglocalpart@company.com", "sub-1");
        assertTrue(username.length() <= 28, username);
    }
}
