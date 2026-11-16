package com.example.kb.security;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.example.kb.common.Visibility;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** 用户管理：登录校验、创建用户、改密码、查用户清单；密码一律 BCrypt 存 hash。 */
@Service
public class UserService {

    private static final Set<String> ROLES = Set.of("ADMIN", "USER");

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;

    public UserService(JdbcTemplate jdbcTemplate, PasswordEncoder passwordEncoder) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
    }

    public record UserRow(UUID id, String username, String passwordHash, String role, String department) {
    }

    public record UserSummary(String username, String role, String department, OffsetDateTime createdAt) {
    }

    /** 用户名密码都对才返回身份，否则空。 */
    public Optional<UserPrincipal> authenticate(String username, String rawPassword) {
        return findByUsername(username)
                .filter(user -> passwordEncoder.matches(rawPassword, user.passwordHash()))
                .map(user -> new UserPrincipal(user.username(), user.role(), user.department()));
    }

    public Optional<UserRow> findByUsername(String username) {
        try {
            UserRow row = jdbcTemplate.queryForObject(
                    "SELECT id, username, password_hash, role, department FROM app_user WHERE username = ?",
                    (rs, rowNum) -> new UserRow(UUID.fromString(rs.getString("id")), rs.getString("username"),
                            rs.getString("password_hash"), rs.getString("role"), rs.getString("department")),
                    username);
            return Optional.ofNullable(row);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public boolean existsAdmin() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM app_user WHERE role = 'ADMIN'", Integer.class);
        return count != null && count > 0;
    }

    public List<UserSummary> listUsers() {
        return jdbcTemplate.query(
                "SELECT username, role, department, created_at FROM app_user ORDER BY username",
                (rs, rowNum) -> new UserSummary(rs.getString("username"), rs.getString("role"),
                        rs.getString("department"), rs.getObject("created_at", OffsetDateTime.class)));
    }

    /** role 不传默认 USER；department 不传表示管理员（无归属部门）。 */
    public UserSummary createUser(String username, String rawPassword, String role, String department) {
        validateUsername(username);
        validatePassword(rawPassword);
        String cleanRole = role == null ? "USER" : role.toUpperCase(Locale.ROOT);
        if (!ROLES.contains(cleanRole)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "role 只允许 ADMIN 或 USER");
        }
        String cleanDepartment = department == null ? null : Visibility.normalize(department);
        try {
            jdbcTemplate.update("INSERT INTO app_user(id, username, password_hash, role, department) VALUES (?, ?, ?, ?, ?)",
                    UUID.randomUUID(), username, passwordEncoder.encode(rawPassword), cleanRole, cleanDepartment);
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "用户名已存在");
        }
        return findByUsername(username)
                .map(user -> new UserSummary(user.username(), user.role(), user.department(), null))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "创建用户后查询失败"));
    }

    public void changePassword(String username, String oldPassword, String newPassword) {
        UserRow user = findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在"));
        if (!passwordEncoder.matches(oldPassword, user.passwordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "原密码错误");
        }
        validatePassword(newPassword);
        jdbcTemplate.update("UPDATE app_user SET password_hash = ? WHERE username = ?",
                passwordEncoder.encode(newPassword), username);
    }

    private static void validateUsername(String username) {
        if (username == null || username.isBlank() || username.length() < 3 || username.length() > 32
                || !username.matches("[A-Za-z0-9_-]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用户名只允许 3-32 位字母、数字、下划线、中划线");
        }
    }

    private static void validatePassword(String password) {
        if (password == null || password.length() < 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "密码至少 8 位");
        }
    }
}
