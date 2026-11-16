package com.example.kb.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class DepartmentScopeTest {

    private static final UserPrincipal ADMIN = new UserPrincipal("admin", "ADMIN", null);
    private static final UserPrincipal HR_USER = new UserPrincipal("zhang", "USER", "hr");

    @Test
    void adminCanQueryAnyDepartment() {
        assertEquals("finance", DepartmentScope.effectiveDepartment(ADMIN, "finance"));
    }

    @Test
    void userDefaultsToOwnDepartment() {
        assertEquals("hr", DepartmentScope.effectiveDepartment(HR_USER, null));
    }

    @Test
    void userCannotQueryOtherDepartment() {
        assertThrows(ResponseStatusException.class,
                () -> DepartmentScope.effectiveDepartment(HR_USER, "finance"));
    }

    @Test
    void uploadBlockedForOtherDepartment() {
        assertThrows(ResponseStatusException.class,
                () -> DepartmentScope.checkUploadVisibility(HR_USER, "finance"));
    }

    @Test
    void uploadAllowedForOwnDepartmentAndAll() {
        DepartmentScope.checkUploadVisibility(HR_USER, "hr");
        DepartmentScope.checkUploadVisibility(HR_USER, "all");
        DepartmentScope.checkUploadVisibility(ADMIN, "finance");
    }
}
