package com.example.kb.controller;

import java.util.Map;

import com.example.kb.dto.ChangePasswordRequest;
import com.example.kb.dto.LoginRequest;
import com.example.kb.dto.LoginResponse;
import com.example.kb.security.DepartmentScope;
import com.example.kb.security.JwtService;
import com.example.kb.security.UserPrincipal;
import com.example.kb.security.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 登录接口：登录拿 Token、改自己的密码。登录接口本身放行，其余接口都要带 Token。 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;
    private final JwtService jwtService;

    public AuthController(UserService userService, JwtService jwtService) {
        this.userService = userService;
        this.jwtService = jwtService;
    }

    /** 用户名密码校验通过发 Token，有效期 8 小时；不对返回 401（不区分是账号错还是密码错）。 */
    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest request) {
        if (request.getUsername() == null || request.getUsername().isBlank()
                || request.getPassword() == null || request.getPassword().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用户名和密码不能为空");
        }
        UserPrincipal principal = userService.authenticate(request.getUsername(), request.getPassword())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误"));
        return new LoginResponse(jwtService.createToken(principal), principal.username(),
                principal.role(), principal.department(), jwtService.validitySeconds());
    }

    /** 改自己的密码：原密码不对 401，新密码至少 8 位。 */
    @PostMapping("/change-password")
    public Map<String, Boolean> changePassword(@RequestBody ChangePasswordRequest request) {
        UserPrincipal user = DepartmentScope.requireCurrentUser();
        userService.changePassword(user.username(), request.getOldPassword(), request.getNewPassword());
        return Map.of("changed", true);
    }
}
