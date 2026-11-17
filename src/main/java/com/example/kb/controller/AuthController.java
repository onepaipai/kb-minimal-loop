package com.example.kb.controller;

import java.util.Map;

import com.example.kb.dto.ChangePasswordRequest;
import com.example.kb.dto.LoginRequest;
import com.example.kb.dto.LoginResponse;
import com.example.kb.dto.RefreshTokenRequest;
import com.example.kb.dto.TotpCodeRequest;
import com.example.kb.dto.TotpPasswordRequest;
import com.example.kb.dto.TotpSetupResponse;
import com.example.kb.service.AuditService;
import com.example.kb.security.DepartmentScope;
import com.example.kb.security.JwtService;
import com.example.kb.security.RefreshTokenService;
import com.example.kb.security.TotpService;
import com.example.kb.security.UserPrincipal;
import com.example.kb.security.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 登录接口：登录拿 Token、改自己的密码、TOTP 双因素的开通与关闭。
 * 登录流程：账号不存在与密码错误返回同一条 401（不透露是哪一个错了）；连续 5 次错锁定 15 分钟；
 * 开了双因素的账号必须再传 6 位动态验证码。登录接口本身放行，其余接口都要带 Token。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final TotpService totpService;
    private final RefreshTokenService refreshTokenService;
    private final AuditService auditService;

    public AuthController(UserService userService, JwtService jwtService, PasswordEncoder passwordEncoder,
                          TotpService totpService, RefreshTokenService refreshTokenService,
                          AuditService auditService) {
        this.userService = userService;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        this.totpService = totpService;
        this.refreshTokenService = refreshTokenService;
        this.auditService = auditService;
    }

    /** 用户名密码校验通过发 Token，有效期 8 小时；同时发 Refresh Token（7 天）。 */
    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest request) {
        if (request.getUsername() == null || request.getUsername().isBlank()
                || request.getPassword() == null || request.getPassword().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用户名和密码不能为空");
        }
        String username = request.getUsername();
        UserService.UserRow user = userService.findByUsername(username).orElse(null);
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.passwordHash())) {
            if (user != null) {
                userService.recordFailedLogin(username);
            }
            auditService.record(username, "LOGIN_FAILED", null, "用户名或密码错误");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }
        if (userService.isLocked(user)) {
            auditService.record(username, "LOGIN_FAILED", null, "账号已锁定");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号已锁定，请 15 分钟后再试");
        }
        if (user.totpEnabled()) {
            if (request.getTotpCode() == null
                    || !totpService.verify(user.totpSecret(), request.getTotpCode())) {
                userService.recordFailedLogin(username);
                auditService.record(username, "LOGIN_FAILED", null, "动态验证码错误或未提供");
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "动态验证码错误或未提供");
            }
        }
        userService.resetFailedLogin(username);
        auditService.record(username, "LOGIN", null, null);
        UserPrincipal principal = new UserPrincipal(user.username(), user.role(), user.department());
        return new LoginResponse(jwtService.createToken(principal), principal.username(),
                principal.role(), principal.department(), jwtService.validitySeconds(),
                refreshTokenService.issue(username));
    }

    /** 用 Refresh Token 换一对新 Token（旧 Refresh Token 轮换吊销）；无效或过期 401。 */
    @PostMapping("/refresh")
    public LoginResponse refresh(@RequestBody RefreshTokenRequest request) {
        if (request.getRefreshToken() == null || request.getRefreshToken().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Refresh Token 不能为空");
        }
        RefreshTokenService.RefreshResult result = refreshTokenService.refresh(request.getRefreshToken())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh Token 无效或已过期"));
        UserService.UserRow user = userService.findByUsername(result.username())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户不存在"));
        auditService.record(user.username(), "REFRESH", null, null);
        UserPrincipal principal = new UserPrincipal(user.username(), user.role(), user.department());
        return new LoginResponse(jwtService.createToken(principal), principal.username(),
                principal.role(), principal.department(), jwtService.validitySeconds(), result.refreshToken());
    }

    /** 退出登录：吊销传来的 Refresh Token；不带或已失效也不报错。 */
    @PostMapping("/logout")
    public Map<String, Boolean> logout(@RequestBody(required = false) RefreshTokenRequest request) {
        if (request != null && request.getRefreshToken() != null && !request.getRefreshToken().isBlank()) {
            refreshTokenService.revoke(request.getRefreshToken());
        }
        return Map.of("loggedOut", true);
    }

    /** 改自己的密码：原密码不对 401，新密码至少 10 位且含字母和数字；改完吊销该用户全部 Token。 */
    @PostMapping("/change-password")
    public Map<String, Boolean> changePassword(@RequestBody ChangePasswordRequest request) {
        UserPrincipal user = DepartmentScope.requireCurrentUser();
        userService.changePassword(user.username(), request.getOldPassword(), request.getNewPassword());
        refreshTokenService.revokeAllForUser(user.username());
        auditService.record(user.username(), "CHANGE_PASSWORD", null, null);
        return Map.of("changed", true);
    }

    /** 生成双因素密钥：返回密钥与 otpauth 链接，用户绑进验证器 App 后再调 enable 启用。 */
    @PostMapping("/totp/setup")
    public TotpSetupResponse setupTotp() {
        UserPrincipal user = DepartmentScope.requireCurrentUser();
        String secret = totpService.generateSecret();
        userService.setTotpSecret(user.username(), secret);
        return new TotpSetupResponse(secret, totpService.otpauthUrl(user.username(), secret));
    }

    /** 验证码通过后启用双因素。 */
    @PostMapping("/totp/enable")
    public Map<String, Boolean> enableTotp(@RequestBody TotpCodeRequest request) {
        UserPrincipal user = DepartmentScope.requireCurrentUser();
        UserService.UserRow row = userService.findByUsername(user.username())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在"));
        if (row.totpSecret() == null || !totpService.verify(row.totpSecret(), request.getCode())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "动态验证码错误");
        }
        userService.enableTotp(user.username());
        return Map.of("enabled", true);
    }

    /** 密码校验通过后关闭双因素并清掉密钥。 */
    @PostMapping("/totp/disable")
    public Map<String, Boolean> disableTotp(@RequestBody TotpPasswordRequest request) {
        UserPrincipal user = DepartmentScope.requireCurrentUser();
        UserService.UserRow row = userService.findByUsername(user.username())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在"));
        if (!passwordEncoder.matches(request.getPassword(), row.passwordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "密码错误");
        }
        userService.disableTotp(user.username());
        return Map.of("disabled", true);
    }
}
