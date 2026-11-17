package com.example.kb.dto;

/** POST /api/auth/totp/disable 入参：关闭双因素时校验一次密码。 */
public class TotpPasswordRequest {

    private String password;

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
