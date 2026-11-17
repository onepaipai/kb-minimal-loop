package com.example.kb.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;

/**
 * OIDC 注册：只在 OIDC_ENABLED=true 时建。启动时会拉取 IdP 的 discovery 文档，
 * 所以开启后 IdP 必须可达；不开时这个配置不存在，应用完全不受影响。
 */
@Configuration
@ConditionalOnProperty(name = "app.oidc.enabled", havingValue = "true")
public class OidcConfig {

    @Bean
    public ClientRegistrationRepository clientRegistrationRepository(OidcProperties props) {
        ClientRegistration registration = ClientRegistrations.fromIssuerLocation(props.getIssuerUri())
                .registrationId("kb")
                .clientId(props.getClientId())
                .clientSecret(props.getClientSecret())
                .scope("openid", "profile", "email")
                .redirectUri("{baseUrl}/login/oauth2/code/kb")
                .build();
        return new InMemoryClientRegistrationRepository(registration);
    }
}
