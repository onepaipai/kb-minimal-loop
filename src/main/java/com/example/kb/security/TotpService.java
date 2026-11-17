package com.example.kb.security;

import java.nio.ByteBuffer;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Service;

/**
 * TOTP 双因素（RFC 6238，Google Authenticator 兼容）：HMAC-SHA1、30 秒一步、6 位数字。
 * 密钥用 Base32 存，校验时前后各容忍一步（手机与服务器时钟有偏差时仍能通过）。
 * SSO（企业微信/钉钉/OIDC）需要企业侧注册 IdP 拿 client 配置，无法在这里凭空接，登录加固先做到这一步。
 */
@Service
public class TotpService {

    private static final int SECRET_BYTES = 20;
    private static final int TIME_STEP_SECONDS = 30;
    private static final int CODE_DIGITS = 6;
    private static final int WINDOW_STEPS = 1;
    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private final Clock clock;

    public TotpService() {
        this(Clock.systemUTC());
    }

    TotpService(Clock clock) {
        this.clock = clock;
    }

    /** 生成 20 字节随机密钥的 Base32 文本，注册双因素时发给用户绑进验证器 App。 */
    public String generateSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        new SecureRandom().nextBytes(bytes);
        return encodeBase32(bytes);
    }

    /** otpauth 链接：用户把密钥手动填进验证器 App，或按链接里的参数扫码。 */
    public String otpauthUrl(String username, String secret) {
        return "otpauth://totp/KB:" + username + "?secret=" + secret + "&issuer=KB&algorithm=SHA1&digits=6&period=30";
    }

    /** 校验 6 位验证码；时钟偏差前后各容忍一步。 */
    public boolean verify(String base32Secret, String code) {
        if (base32Secret == null || code == null || !code.matches("\\d{6}")) {
            return false;
        }
        byte[] key = decodeBase32(base32Secret);
        long step = Instant.now(clock).getEpochSecond() / TIME_STEP_SECONDS;
        for (long offset = -WINDOW_STEPS; offset <= WINDOW_STEPS; offset++) {
            if (code.equals(generateCode(key, step + offset))) {
                return true;
            }
        }
        return false;
    }

    private String generateCode(byte[] key, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);
            int code = binary % (int) Math.pow(10, CODE_DIGITS);
            return String.format("%0" + CODE_DIGITS + "d", code);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("TOTP 计算失败", e);
        }
    }

    static String encodeBase32(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bitsLeft = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                bitsLeft -= 5;
                out.append(BASE32_ALPHABET.charAt((buffer >> bitsLeft) & 0x1F));
            }
        }
        if (bitsLeft > 0) {
            out.append(BASE32_ALPHABET.charAt((buffer << (5 - bitsLeft)) & 0x1F));
        }
        return out.toString();
    }

    static byte[] decodeBase32(String base32) {
        String clean = base32.replace("=", "").toUpperCase();
        byte[] out = new byte[clean.length() * 5 / 8];
        int buffer = 0;
        int bitsLeft = 0;
        int index = 0;
        for (int i = 0; i < clean.length(); i++) {
            int value = BASE32_ALPHABET.indexOf(clean.charAt(i));
            if (value < 0) {
                throw new IllegalArgumentException("Base32 密钥含非法字符");
            }
            buffer = (buffer << 5) | value;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                bitsLeft -= 8;
                out[index++] = (byte) ((buffer >> bitsLeft) & 0xFF);
            }
        }
        return out;
    }
}
