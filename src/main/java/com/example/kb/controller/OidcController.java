package com.example.kb.controller;

import com.example.kb.dto.LoginResponse;
import com.example.kb.dto.OidcCodeRequest;
import com.example.kb.dto.OidcStatusResponse;
import com.example.kb.security.JwtService;
import com.example.kb.security.OidcCodeService;
import com.example.kb.security.OidcProperties;
import com.example.kb.security.RefreshTokenService;
import com.example.kb.security.UserPrincipal;
import com.example.kb.security.UserService;
import com.example.kb.service.AuditService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** OIDC 企业登录：换码发 Token、查是否开启。IdP 侧的登录与回调走 Spring Security 的 /oauth2/authorization/kb。 */
@RestController
@RequestMapping("/api/auth/oidc")
public class OidcController {

    private final OidcProperties oidcProperties;
    private final OidcCodeService oidcCodeService;
    private final UserService userService;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final AuditService auditService;

    public OidcController(OidcProperties oidcProperties, OidcCodeService oidcCodeService, UserService userService,
                          JwtService jwtService, RefreshTokenService refreshTokenService, AuditService auditService) {
        this.oidcProperties = oidcProperties;
        this.oidcCodeService = oidcCodeService;
        this.userService = userService;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
        this.auditService = auditService;
    }

    /** 登录页问一句 OIDC 开没开，没开就不显示企业登录按钮。 */
    @GetMapping("/enabled")
    public OidcStatusResponse enabled() {
        return new OidcStatusResponse(oidcProperties.isEnabled());
    }

    /** 凭回调发来的一次性码换一对 Token；码不存在、已用、过期都报 401。 */
    @PostMapping("/exchange")
    public LoginResponse exchange(@RequestBody OidcCodeRequest request) {
        String username = oidcCodeService.consume(request.getCode())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录码无效或已过期"));
        UserService.UserRow user = userService.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户不存在"));
        UserPrincipal principal = new UserPrincipal(user.username(), user.role(), user.department());
        auditService.record(username, "OIDC_LOGIN", username, "role=" + user.role());
        return new LoginResponse(jwtService.createToken(principal), principal.username(), principal.role(),
                principal.department(), jwtService.validitySeconds(), refreshTokenService.issue(username));
    }
}
