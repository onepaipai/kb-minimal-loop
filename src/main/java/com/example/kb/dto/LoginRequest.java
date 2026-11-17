package com.example.kb.dto;

/** POST /api/auth/login 入参：开了双因素的账号多传 totpCode（6 位动态验证码）。 */
public class LoginRequest {

    private String username;
    private String password;
    private String totpCode;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getTotpCode() {
        return totpCode;
    }

    public void setTotpCode(String totpCode) {
        this.totpCode = totpCode;
    }
}
