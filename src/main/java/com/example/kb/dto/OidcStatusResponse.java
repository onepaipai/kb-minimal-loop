package com.example.kb.dto;

/** OIDC 是否开启：登录页据此决定显不显示“企业账号登录”按钮。 */
public class OidcStatusResponse {

    private final boolean enabled;

    public OidcStatusResponse(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
