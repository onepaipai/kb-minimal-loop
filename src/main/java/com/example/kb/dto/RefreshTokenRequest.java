package com.example.kb.dto;

/** POST /api/auth/refresh 与 /api/auth/logout 入参。 */
public class RefreshTokenRequest {

    private String refreshToken;

    public String getRefreshToken() {
        return refreshToken;
    }

    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }
}
