package com.example.kb.security;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 四角色权限：上传、删除/审批、初始审批状态。 */
class RoleScopeTest {

    private static final UserPrincipal ADMIN = new UserPrincipal("root", "ADMIN", null);
    private static final UserPrincipal DEPT_ADMIN = new UserPrincipal("lead", "DEPT_ADMIN", "tech");
    private static final UserPrincipal EDITOR = new UserPrincipal("ed", "EDITOR", "tech");
    private static final UserPrincipal USER = new UserPrincipal("u", "USER", "tech");

    @Test
    void userCannotUpload() {
        assertThrows(ResponseStatusException.class, () -> DepartmentScope.requireCanUpload(USER));
        assertDoesNotThrow(() -> DepartmentScope.requireCanUpload(EDITOR));
        assertDoesNotThrow(() -> DepartmentScope.requireCanUpload(DEPT_ADMIN));
        assertDoesNotThrow(() -> DepartmentScope.requireCanUpload(ADMIN));
    }

    @Test
    void deleteNeedsOwnDepartment() {
        assertDoesNotThrow(() -> DepartmentScope.requireCanDeleteDocument(ADMIN, "hr"));
        assertDoesNotThrow(() -> DepartmentScope.requireCanDeleteDocument(DEPT_ADMIN, "tech"));
        assertThrows(ResponseStatusException.class,
                () -> DepartmentScope.requireCanDeleteDocument(DEPT_ADMIN, "hr"));
        assertThrows(ResponseStatusException.class,
                () -> DepartmentScope.requireCanDeleteDocument(EDITOR, "tech"));
        assertThrows(ResponseStatusException.class,
                () -> DepartmentScope.requireCanDeleteDocument(USER, "tech"));
    }

    @Test
    void initialStatus() {
        assertEquals("approved", DepartmentScope.initialDocStatus(ADMIN, "all"));
        assertEquals("approved", DepartmentScope.initialDocStatus(DEPT_ADMIN, "tech"));
        assertEquals("pending", DepartmentScope.initialDocStatus(DEPT_ADMIN, "all"));
        assertEquals("pending", DepartmentScope.initialDocStatus(EDITOR, "tech"));
        assertEquals("pending", DepartmentScope.initialDocStatus(USER, "tech"));
    }
}
