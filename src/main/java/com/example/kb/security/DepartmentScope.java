package com.example.kb.security;

import com.example.kb.common.Visibility;
import com.example.kb.service.RagService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

/**
 * 权限的集中判断：问答/清单的生效部门、入库与删除的可见性校验、审批与上传的角色判断、当前登录用户。
 * 角色：ADMIN 全局、DEPT_ADMIN 管本部门、EDITOR 可上传待审批、USER 只能问答。
 */
public final class DepartmentScope {

    private DepartmentScope() {
    }

    /** 当前登录用户；未登录返回 null（只应出现在已放行的 /api/auth/login 上）。 */
    public static UserPrincipal currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof UserPrincipal principal) {
            return principal;
        }
        return null;
    }

    /** 未登录直接 401。 */
    public static UserPrincipal requireCurrentUser() {
        UserPrincipal user = currentUser();
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或登录已过期");
        }
        return user;
    }

    /**
     * 问答/清单的生效部门：有归属部门的用户只能查本部门（传别的部门报 403），管理员用请求里带的部门。
     */
    public static String effectiveDepartment(UserPrincipal user, String requested) {
        if (user != null && user.department() != null) {
            if (requested != null && !requested.equals(user.department())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只能访问本部门的文档");
            }
            return user.department();
        }
        return requested;
    }

    /** 入库的可见性校验：非管理员只能上传全员可见或本部门的文档。 */
    public static void checkUploadVisibility(UserPrincipal user, String visibility) {
        if (Visibility.ALL.equals(visibility) || user == null || user.isAdmin()) {
            return;
        }
        if (user.department() != null && visibility.equals(user.department())) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只能上传本部门或全员可见的文档");
    }

    /** 上传权限：普通用户不能上传（只读问答）。 */
    public static void requireCanUpload(UserPrincipal user) {
        if (user == null || !user.canUpload()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "普通用户没有上传权限");
        }
    }

    /** 删除权限：管理员可删全部，部门管理员只删本部门的文档。 */
    public static void requireCanDeleteDocument(UserPrincipal user, String documentVisibility) {
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或登录已过期");
        }
        if (user.isAdmin()) {
            return;
        }
        if (user.isDeptAdmin() && user.department() != null && user.department().equals(documentVisibility)) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只能删除本部门的文档");
    }

    /** 审批权限：与删除同规则，管理员全部，部门管理员只批本部门的。 */
    public static void requireCanApprove(UserPrincipal user, String documentVisibility) {
        requireCanDeleteDocument(user, documentVisibility);
    }

    /**
     * 入库后的初始审批状态：管理员直接发布；部门管理员传本部门的文档直接发布，
     * 其余一律待审批（全员可见的文档须管理员批，编辑上传的须管理员或部门管理员批）。
     */
    public static String initialDocStatus(UserPrincipal user, String visibility) {
        if (user == null) {
            return "pending";
        }
        if (user.isAdmin()) {
            return "approved";
        }
        if (user.isDeptAdmin() && user.department() != null && user.department().equals(visibility)) {
            return "approved";
        }
        return "pending";
    }

    /** 问答的作用域：用户名进会话键，部门按上面的规则算好。 */
    public static RagService.AskScope askScope(UserPrincipal user, String requested) {
        String department = effectiveDepartment(user, requested);
        return new RagService.AskScope(user == null ? "anonymous" : user.username(), department);
    }
}
