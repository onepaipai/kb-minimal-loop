package com.example.kb.dto;

/** POST /api/auth/totp/enable 入参：验证器 App 里的 6 位验证码。 */
public class TotpCodeRequest {

    private String code;

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }
}
