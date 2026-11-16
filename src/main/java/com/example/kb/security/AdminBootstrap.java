package com.example.kb.security;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 初始管理员播种：库里没有任何 ADMIN 时创建一个。
 * 账号密码走环境变量 ADMIN_USERNAME（默认 admin）/ ADMIN_PASSWORD；不配密码则随机生成并打在启动日志里（与 Spring Boot 默认生成密码同一做法），登录后请立即修改。
 */
@Component
public class AdminBootstrap {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final UserService userService;

    @Value("${ADMIN_USERNAME:admin}")
    private String adminUsername;

    @Value("${ADMIN_PASSWORD:}")
    private String adminPassword;

    public AdminBootstrap(UserService userService) {
        this.userService = userService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seedAdminIfMissing() {
        if (userService.existsAdmin()) {
            return;
        }
        if (userService.findByUsername(adminUsername).isPresent()) {
            log.error("初始管理员账号 {} 已被普通用户占用，请换一个 ADMIN_USERNAME", adminUsername);
            return;
        }
        String password = adminPassword.isBlank()
                ? UUID.randomUUID().toString().replace("-", "").substring(0, 16)
                : adminPassword;
        userService.createUser(adminUsername, password, "ADMIN", null);
        if (adminPassword.isBlank()) {
            log.warn("未配置 ADMIN_PASSWORD，已随机生成初始管理员密码，登录后请立即修改：账号 {}，密码 {}", adminUsername, password);
        } else {
            log.info("已创建初始管理员账号：{}", adminUsername);
        }
    }
}
