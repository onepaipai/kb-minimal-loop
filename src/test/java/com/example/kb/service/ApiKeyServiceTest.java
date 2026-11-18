package com.example.kb.service;

import java.util.List;
import java.util.Optional;

import com.example.kb.security.UserPrincipal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** API Key：创建只存哈希、校验返回主人身份、吊销后失效；用 mock 的 JdbcTemplate 核对，不碰真实库。 */
@ExtendWith(MockitoExtension.class)
class ApiKeyServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private ApiKeyService service() {
        return new ApiKeyService(jdbcTemplate);
    }

    @Test
    void createStoresOnlyHash() {
        ApiKeyService.CreatedKey created = service().create("test-key", "admin", null, null);
        assertTrue(created.rawKey().startsWith(ApiKeyService.KEY_PREFIX));
        assertEquals(ApiKeyService.KEY_PREFIX.length() + 32, created.rawKey().length());
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(anyString(), args.capture());
        assertEquals(ApiKeyService.sha256Hex(created.rawKey()), args.getValue()[1]);
        assertEquals("test-key", args.getValue()[2]);
        assertEquals("admin", args.getValue()[3]);
        assertEquals(ApiKeyService.SCOPE_ALL, args.getValue()[4]);
        assertEquals(null, args.getValue()[5]);
    }

    @Test
    void createStoresScopeAndExpiry() {
        service().create("ask-key", "admin", "ask", 90);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(anyString(), args.capture());
        assertEquals(ApiKeyService.SCOPE_ASK, args.getValue()[4]);
        assertTrue(args.getValue()[5] != null);
    }

    @Test
    void createRejectsBadScopeAndExpiry() {
        assertThrows(IllegalArgumentException.class,
                () -> service().create("k", "admin", "write", null));
        assertThrows(IllegalArgumentException.class,
                () -> service().create("k", "admin", "all", 0));
        assertThrows(IllegalArgumentException.class,
                () -> service().create("k", "admin", "all", ApiKeyService.MAX_EXPIRES_DAYS + 1));
    }

    @Test
    void validateReturnsOwnerAndScopeAndTouchesLastUsed() {
        UserPrincipal owner = new UserPrincipal("editor", "EDITOR", null);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(new ApiKeyService.ValidatedKey(owner, ApiKeyService.SCOPE_ASK)));
        Optional<ApiKeyService.ValidatedKey> result = service().validate("kb_abc");
        assertTrue(result.isPresent());
        assertEquals(owner, result.get().principal());
        assertEquals(ApiKeyService.SCOPE_ASK, result.get().scope());
        verify(jdbcTemplate).update(anyString(), any(Object[].class));
    }

    @Test
    void validateRejectsUnknownKey() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        assertEquals(Optional.empty(), service().validate("kb_nope"));
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void revokeReturnsWhetherRowChanged() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1, 0);
        ApiKeyService service = service();
        assertTrue(service.revoke(java.util.UUID.randomUUID()));
        assertFalse(service.revoke(java.util.UUID.randomUUID()));
    }
}
