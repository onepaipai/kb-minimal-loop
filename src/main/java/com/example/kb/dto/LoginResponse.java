package com.example.kb.dto;

/** POST /api/auth/login 出参：expiresIn 为 Token 有效秒数。 */
public class LoginResponse {

    private final String token;
    private final String username;
    private final String role;
    private final String department;
    private final long expiresIn;

    public LoginResponse(String token, String username, String role, String department, long expiresIn) {
        this.token = token;
        this.username = username;
        this.role = role;
        this.department = department;
        this.expiresIn = expiresIn;
    }

    public String getToken() {
        return token;
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
