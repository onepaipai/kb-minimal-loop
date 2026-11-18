package com.example.kb.security;

import java.util.Optional;

import com.example.kb.service.ApiKeyService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** API Key 范围拦：ask 的 Key 只能调问答与清单，调入库不给身份（下游按未登录 401）；all 的 Key 不拦。 */
@ExtendWith(MockitoExtension.class)
class ApiKeyAuthFilterTest {

    @Mock
    private ApiKeyService apiKeyService;
    @Mock
    private FilterChain filterChain;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private ApiKeyAuthFilter filter() {
        return new ApiKeyAuthFilter(apiKeyService);
    }

    private MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.addHeader("X-API-Key", "kb_test");
        return request;
    }

    @Test
    void askScopeKeyCanAskButNotIngest() throws Exception {
        UserPrincipal owner = new UserPrincipal("editor", "EDITOR", null);
        when(apiKeyService.validate(anyString()))
                .thenReturn(Optional.of(new ApiKeyService.ValidatedKey(owner, ApiKeyService.SCOPE_ASK)));

        filter().doFilter(request("POST", "/api/ask"), new MockHttpServletResponse(), filterChain);
        assertEquals("editor", ((UserPrincipal) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal()).username());

        SecurityContextHolder.clearContext();
        filter().doFilter(request("POST", "/api/ingest"), new MockHttpServletResponse(), filterChain);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(filterChain, org.mockito.Mockito.times(2)).doFilter(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void allScopeKeyCanIngest() throws Exception {
        UserPrincipal owner = new UserPrincipal("editor", "EDITOR", null);
        when(apiKeyService.validate(anyString()))
                .thenReturn(Optional.of(new ApiKeyService.ValidatedKey(owner, ApiKeyService.SCOPE_ALL)));
        filter().doFilter(request("POST", "/api/ingest"), new MockHttpServletResponse(), filterChain);
        assertEquals("editor", ((UserPrincipal) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal()).username());
    }

    @Test
    void noKeyPassesThroughWithoutAuth() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/ask");
        filter().doFilter(request, new MockHttpServletResponse(), filterChain);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }
}
