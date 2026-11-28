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

/**
 * 用户管理：登录校验、创建用户、改密码、查用户清单；密码一律 BCrypt 存 hash。
 * 角色四档：ADMIN 全局、DEPT_ADMIN 管本部门、EDITOR 可上传待审批、USER 只能问答。
 * 登录加固：连续 5 次密码错误锁定 15 分钟；TOTP 双因素在 AuthController 里校验，这里只管存取。
 */
@Service
public class UserService {

    private static final Set<String> ROLES = Set.of("ADMIN", "DEPT_ADMIN", "EDITOR", "USER");
    /** 连续失败这么多次后锁定账号 */
    public static final int MAX_FAILED_ATTEMPTS = 5;
    /** 锁定时长（分钟） */
    public static final int LOCK_MINUTES = 15;

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;

    public UserService(JdbcTemplate jdbcTemplate, PasswordEncoder passwordEncoder) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
    }

    public record UserRow(UUID id, String username, String passwordHash, String role, String department,
                          String totpSecret, boolean totpEnabled, int failedAttempts, OffsetDateTime lockedUntil,
                          String oidcSubject) {
    }

    public record UserSummary(String username, String role, String department, OffsetDateTime createdAt) {
    }

    public Optional<UserRow> findByUsername(String username) {
        try {
            UserRow row = jdbcTemplate.queryForObject(
                    "SELECT id, username, password_hash, role, department, totp_secret, totp_enabled, "
                            + "failed_attempts, locked_until, oidc_subject FROM app_user WHERE username = ?",
                    (rs, rowNum) -> new UserRow(UUID.fromString(rs.getString("id")), rs.getString("username"),
                            rs.getString("password_hash"), rs.getString("role"), rs.getString("department"),
                            rs.getString("totp_secret"), rs.getBoolean("totp_enabled"),
                            rs.getInt("failed_attempts"), rs.getObject("locked_until", OffsetDateTime.class),
                            rs.getString("oidc_subject")),
                    username);
            return Optional.ofNullable(row);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** 按 IdP 的 subject 找 OIDC 用户；subject 在库里唯一，一个企业账号只对应一个内部账号。 */
    public Optional<UserRow> findByOidcSubject(String subject) {
        try {
            UserRow row = jdbcTemplate.queryForObject(
                    "SELECT id, username, password_hash, role, department, totp_secret, totp_enabled, "
                            + "failed_attempts, locked_until, oidc_subject FROM app_user WHERE oidc_subject = ?",
                    (rs, rowNum) -> new UserRow(UUID.fromString(rs.getString("id")), rs.getString("username"),
                            rs.getString("password_hash"), rs.getString("role"), rs.getString("department"),
                            rs.getString("totp_secret"), rs.getBoolean("totp_enabled"),
                            rs.getInt("failed_attempts"), rs.getObject("locked_until", OffsetDateTime.class),
                            rs.getString("oidc_subject")),
                    subject);
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

    /**
     * 用户清单：管理员看全部，部门管理员只看本部门的人（自己的账号也在其中）。
     * 其他角色不调这个接口，调了也只返回空清单。
     */
    public List<UserSummary> listUsers(UserPrincipal scope) {
        if (scope != null && scope.isDeptAdmin()) {
            return jdbcTemplate.query(
                    "SELECT username, role, department, created_at FROM app_user WHERE department = ? ORDER BY username",
                    (rs, rowNum) -> new UserSummary(rs.getString("username"), rs.getString("role"),
                            rs.getString("department"), rs.getObject("created_at", OffsetDateTime.class)),
                    scope.department());
        }
        if (scope != null && !scope.isAdmin()) {
            return List.of();
        }
        return jdbcTemplate.query(
                "SELECT username, role, department, created_at FROM app_user ORDER BY username",
                (rs, rowNum) -> new UserSummary(rs.getString("username"), rs.getString("role"),
                        rs.getString("department"), rs.getObject("created_at", OffsetDateTime.class)));
    }

    /**
     * role 不传默认 USER；ADMIN 必须无部门，DEPT_ADMIN 必须有所属部门。
     * 密码策略：至少 10 位且含字母和数字。
     */
    public UserSummary createUser(String username, String rawPassword, String role, String department) {
        validateUsername(username);
        PasswordPolicy.validate(rawPassword);
        String cleanRole = role == null ? "USER" : role.toUpperCase(Locale.ROOT);
        if (!ROLES.contains(cleanRole)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "role 只允许 ADMIN、DEPT_ADMIN、EDITOR、USER");
        }
        String cleanDepartment = department == null ? null : Visibility.normalize(department);
        if ("ADMIN".equals(cleanRole) && cleanDepartment != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ADMIN 不能有所属部门");
        }
        if ("DEPT_ADMIN".equals(cleanRole) && cleanDepartment == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "DEPT_ADMIN 必须有所属部门");
        }
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

    /**
     * OIDC 用户首次登录时建账号：默认 USER、无部门，密码是随机串（不知道，无法用密码登录）；
     * 管理员事后用改用户接口调角色和部门。
     */
    public UserSummary createOidcUser(String username, String subject) {
        validateUsername(username);
        try {
            jdbcTemplate.update("INSERT INTO app_user(id, username, password_hash, role, department, oidc_subject) "
                            + "VALUES (?, ?, ?, 'USER', NULL, ?)",
                    UUID.randomUUID(), username, passwordEncoder.encode(UUID.randomUUID().toString()), subject);
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "用户名或企业账号已存在");
        }
        return findByUsername(username)
                .map(user -> new UserSummary(user.username(), user.role(), user.department(), null))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "创建用户后查询失败"));
    }

    /** 改用户的角色与部门：校验规则与创建一致；不存在报 404。仅管理员调。 */
    public UserSummary updateUser(String username, String role, String department) {
        UserRow user = findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在"));
        String cleanRole = role == null ? user.role() : role.toUpperCase(Locale.ROOT);
        if (!ROLES.contains(cleanRole)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "role 只允许 ADMIN、DEPT_ADMIN、EDITOR、USER");
        }
        // department 不传保持原值，传空串表示清空；升 ADMIN 时部门自动清空
        String cleanDepartment;
        if ("ADMIN".equals(cleanRole)) {
            cleanDepartment = null;
        } else if (department == null) {
            cleanDepartment = user.department();
        } else if (department.isBlank()) {
            cleanDepartment = null;
        } else {
            cleanDepartment = Visibility.normalize(department);
        }
        if ("DEPT_ADMIN".equals(cleanRole) && cleanDepartment == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "DEPT_ADMIN 必须有所属部门");
        }
        jdbcTemplate.update("UPDATE app_user SET role = ?, department = ? WHERE username = ?",
                cleanRole, cleanDepartment, username);
        return findByUsername(username)
                .map(u -> new UserSummary(u.username(), u.role(), u.department(), null))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "改用户后查询失败"));
    }

    public void changePassword(String username, String oldPassword, String newPassword) {
        UserRow user = findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在"));
        if (!passwordEncoder.matches(oldPassword, user.passwordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "原密码错误");
        }
        PasswordPolicy.validate(newPassword);
        jdbcTemplate.update("UPDATE app_user SET password_hash = ? WHERE username = ?",
                passwordEncoder.encode(newPassword), username);
    }

    /** 账号是否在锁定期内（locked_until 在未来）。 */
    public boolean isLocked(UserRow user) {
        return user.lockedUntil() != null && user.lockedUntil().isAfter(OffsetDateTime.now());
    }

    /** 登录失败用单条条件更新，避免并发错误登录覆盖失败次数。 */
    public void recordFailedLogin(String username) {
        jdbcTemplate.update("UPDATE app_user SET failed_attempts = CASE "
                        + "WHEN locked_until IS NOT NULL AND locked_until <= now() THEN 1 "
                        + "ELSE failed_attempts + 1 END, locked_until = CASE "
                        + "WHEN locked_until IS NOT NULL AND locked_until > now() THEN locked_until "
                        + "WHEN (CASE WHEN locked_until IS NOT NULL AND locked_until <= now() THEN 1 "
                        + "ELSE failed_attempts + 1 END) >= ? "
                        + "THEN now() + (?::text || ' minutes')::interval ELSE NULL END WHERE username = ?",
                MAX_FAILED_ATTEMPTS, LOCK_MINUTES, username);
    }

    /** 登录成功清零失败计数与锁定。 */
    public void resetFailedLogin(String username) {
        jdbcTemplate.update("UPDATE app_user SET failed_attempts = 0, locked_until = NULL WHERE username = ?", username);
    }

    /** 存待启用的 TOTP 密钥（启用前不生效）。 */
    public void setTotpSecret(String username, String secret) {
        jdbcTemplate.update("UPDATE app_user SET totp_secret = ? WHERE username = ?", secret, username);
    }

    /** 验证码通过后启用双因素。 */
    public void enableTotp(String username) {
        jdbcTemplate.update("UPDATE app_user SET totp_enabled = TRUE WHERE username = ?", username);
    }

    /** 关闭双因素并清掉密钥。 */
    public void disableTotp(String username) {
        jdbcTemplate.update("UPDATE app_user SET totp_enabled = FALSE, totp_secret = NULL WHERE username = ?", username);
    }

    private static void validateUsername(String username) {
        if (username == null || username.isBlank() || username.length() < 3 || username.length() > 32
                || !username.matches("[A-Za-z0-9_-]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用户名只允许 3-32 位字母、数字、下划线、中划线");
        }
    }
}
