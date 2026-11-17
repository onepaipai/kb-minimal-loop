package com.example.kb.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * OIDC 企业登录配置：默认关闭，未配 IdP 时整个 OIDC 链路不存在，应用照常启动。
 * 开启需配 4 个环境变量：OIDC_ENABLED=true、OIDC_CLIENT_ID、OIDC_CLIENT_SECRET、OIDC_ISSUER_URI。
 * 回调地址固定为 {baseUrl}/login/oauth2/code/kb，注册 IdP 应用时按这个填。
 */
@Component
@ConfigurationProperties("app.oidc")
public class OidcProperties {

    private boolean enabled;
    private String clientId;
    private String clientSecret;
    private String issuerUri;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    public String getIssuerUri() {
        return issuerUri;
    }

    public void setIssuerUri(String issuerUri) {
        this.issuerUri = issuerUri;
    }
}
