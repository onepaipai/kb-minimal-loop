package com.example.kb.security;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** TOTP：RFC 6238 附录 B 的测试向量（SHA1，密钥为 ASCII 的 12345678901234567890，取 6 位）。 */
class TotpServiceTest {

    /** "12345678901234567890" 的 Base32。 */
    private static final String RFC_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    private TotpService serviceAt(long epochSecond) {
        return new TotpService(Clock.fixed(Instant.ofEpochSecond(epochSecond), ZoneOffset.UTC));
    }

    @Test
    void rfcVectors() {
        assertTrue(serviceAt(59L).verify(RFC_SECRET, "287082"));
        assertTrue(serviceAt(1111111109L).verify(RFC_SECRET, "081804"));
        assertTrue(serviceAt(1111111111L).verify(RFC_SECRET, "050471"));
    }

    @Test
    void wrongCodeFails() {
        assertFalse(serviceAt(59L).verify(RFC_SECRET, "287083"));
    }

    @Test
    void malformedCodeFails() {
        assertFalse(serviceAt(59L).verify(RFC_SECRET, "28708"));
        assertFalse(serviceAt(59L).verify(RFC_SECRET, "abcdef"));
        assertFalse(serviceAt(59L).verify(null, "287082"));
    }

    @Test
    void generatedSecretRoundTrips() throws Exception {
        TotpService service = serviceAt(59L);
        String secret = service.generateSecret();
        assertEquals(32, secret.length());
        // 用测试里独立算的码校验生成密钥可用（算法正确性由上面的 RFC 向量兜底）
        assertTrue(service.verify(secret, independentCode(secret, 59L)));
    }

    /** 测试内独立实现的 TOTP（与被测类同一算法、另写一遍），只用于验证生成密钥可用。 */
    private static String independentCode(String base32Secret, long epochSecond) throws Exception {
        byte[] key = TotpService.decodeBase32(base32Secret);
        long step = epochSecond / 30;
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA1");
        mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA1"));
        byte[] hash = mac.doFinal(java.nio.ByteBuffer.allocate(8).putLong(step).array());
        int offset = hash[hash.length - 1] & 0x0F;
        int binary = ((hash[offset] & 0x7F) << 24) | ((hash[offset + 1] & 0xFF) << 16)
                | ((hash[offset + 2] & 0xFF) << 8) | (hash[offset + 3] & 0xFF);
        return String.format("%06d", binary % 1000000);
    }

    @Test
    void otpauthUrlContainsSecret() {
        TotpService service = new TotpService();
        String url = service.otpauthUrl("alice", "JBSWY3DPEHPK3PXP");
        assertTrue(url.startsWith("otpauth://totp/KB:alice?secret=JBSWY3DPEHPK3PXP"));
    }
}
