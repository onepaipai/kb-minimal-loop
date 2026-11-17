package com.example.kb.dto;

/** POST /api/auth/totp/setup 出参：密钥与 otpauth 链接，用户绑进验证器 App。 */
public class TotpSetupResponse {

    private String secret;
    private String otpauthUrl;

    public TotpSetupResponse(String secret, String otpauthUrl) {
        this.secret = secret;
        this.otpauthUrl = otpauthUrl;
    }

    public String getSecret() {
        return secret;
    }

    public String getOtpauthUrl() {
        return otpauthUrl;
    }
}
