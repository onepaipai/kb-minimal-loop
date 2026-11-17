package com.example.kb.security;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

/**
 * OIDC 登录成功后：按 IdP 的 subject 找人，找不到就新建一个 USER 账号（密码随机、无法用密码登录），
 * 然后发一个 5 分钟一次性换码跳回首页，前端凭码换 Token。Token 本身不进跳转地址。
 */
@Component
public class OidcLoginSuccessHandler implements AuthenticationSuccessHandler {

    private final UserService userService;
    private final OidcCodeService oidcCodeService;

    public OidcLoginSuccessHandler(UserService userService, OidcCodeService oidcCodeService) {
        this.userService = userService;
        this.oidcCodeService = oidcCodeService;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {
        OidcUser oidcUser = (OidcUser) authentication.getPrincipal();
        String subject = oidcUser.getSubject();
        UserService.UserSummary user = userService.findByOidcSubject(subject)
                .map(row -> new UserService.UserSummary(row.username(), row.role(), row.department(), null))
                .orElseGet(() -> provision(oidcUser, subject));
        String code = oidcCodeService.issue(user.username());
        response.sendRedirect("/?oidcCode=" + URLEncoder.encode(code, StandardCharsets.UTF_8));
    }

    private UserService.UserSummary provision(OidcUser oidcUser, String subject) {
        String username = deriveUsername(oidcUser.getEmail(), subject);
        Optional<UserService.UserRow> clash = userService.findByUsername(username);
        if (clash.isPresent() && !subject.equals(clash.get().oidcSubject())) {
            // 用户名被别人先占了：拼一段 subject 哈希后缀，不抢别人的账号
            username = username + "-" + OidcCodeService.sha256Hex(subject).substring(0, 4);
        }
        return userService.createOidcUser(username, subject);
    }

    /** 邮箱本地部分做用户名（只留字母数字下划线中划线）；没有邮箱就用 subject 哈希拼一个。 */
    static String deriveUsername(String email, String subject) {
        String base = email != null && email.contains("@") ? email.substring(0, email.indexOf('@')) : null;
        if (base == null || base.isBlank()) {
            base = "oidc-" + OidcCodeService.sha256Hex(subject).substring(0, 12);
        }
        String clean = base.replaceAll("[^A-Za-z0-9_-]", "_");
        if (clean.length() > 28) {
            clean = clean.substring(0, 28);
        }
        if (clean.length() < 3) {
            clean = "oidc-" + clean;
        }
        return clean;
    }
}
