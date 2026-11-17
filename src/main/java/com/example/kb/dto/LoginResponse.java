package com.example.kb.dto;

/** POST /api/auth/login 出参：expiresIn 为 Access Token 有效秒数，refreshToken 有效 7 天。 */
public class LoginResponse {

    private final String token;
    private final String refreshToken;
    private final String username;
    private final String role;
    private final String department;
    private final long expiresIn;

    public LoginResponse(String token, String username, String role, String department, long expiresIn,
                         String refreshToken) {
        this.token = token;
        this.refreshToken = refreshToken;
        this.username = username;
        this.role = role;
        this.department = department;
        this.expiresIn = expiresIn;
    }

    public String getToken() {
        return token;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public String getUsername() {
        return username;
    }

    public String getRole() {
        return role;
    }

    public String getDepartment() {
        return department;
    }

    public long getExpiresIn() {
        return expiresIn;
    }
}
