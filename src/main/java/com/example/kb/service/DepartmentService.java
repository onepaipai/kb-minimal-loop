package com.example.kb.service;

import java.util.List;

import com.example.kb.common.Visibility;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 部门注册表：代号主键，parent_code 搭组织架构树。
 * 上传时用到未登记的代号自动建一条（name 默认为代号），管理员可在部门管理里改名、挂父部门；
 * 已有文档在用的部门不允许删除（先删文档或改可见性）。
 */
@Service
public class DepartmentService {

    private final JdbcTemplate jdbcTemplate;

    public DepartmentService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record Department(String code, String name, String parentCode) {
    }

    public List<Department> listDepartments() {
        return jdbcTemplate.query("SELECT code, name, parent_code FROM department ORDER BY code",
                (rs, rowNum) -> new Department(rs.getString("code"), rs.getString("name"), rs.getString("parent_code")));
    }

    public boolean exists(String code) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM department WHERE code = ?", Integer.class, code);
        return count != null && count > 0;
    }

    /** 上传时用到未登记的代号自动建一条，保证上传流程不断。 */
    public void ensureDepartment(String code) {
        if (Visibility.ALL.equals(code) || exists(code)) {
            return;
        }
        jdbcTemplate.update("INSERT INTO department(code, name) VALUES (?, ?) ON CONFLICT (code) DO NOTHING",
                code, code);
    }

    public Department createDepartment(String code, String name, String parentCode) {
        String cleanCode = Visibility.normalize(code);
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "部门名称不能为空");
        }
        String cleanParent = parentCode == null || parentCode.isBlank() ? null : Visibility.normalize(parentCode);
        if (cleanCode.equals(cleanParent)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "部门不能把自己当父部门");
        }
        if (cleanParent != null && !exists(cleanParent)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "父部门不存在");
        }
        try {
            jdbcTemplate.update("INSERT INTO department(code, name, parent_code) VALUES (?, ?, ?)",
                    cleanCode, name.trim(), cleanParent);
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "部门代号已存在");
        }
        return new Department(cleanCode, name.trim(), cleanParent);
    }

    /** 删部门前检查：仍有文档挂在这个可见性下不许删。 */
    public void deleteDepartment(String code) {
        Integer docs = jdbcTemplate.queryForObject(
                "SELECT count(DISTINCT metadata->>'source') FROM chunk_embedding WHERE metadata->>'visibility' = ?",
                Integer.class, code);
        if (docs != null && docs > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该部门下仍有 " + docs + " 篇文档，不可删除");
        }
        int deleted = jdbcTemplate.update("DELETE FROM department WHERE code = ?", code);
        if (deleted == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "部门不存在");
        }
    }

    /**
     * 启动时回填：把已入库块里出现过的 visibility 代号登记进来（老数据没有走过部门表）。
     * 已登记的不动，幂等。
     */
    public int seedFromChunks() {
        return jdbcTemplate.update("INSERT INTO department(code, name) "
                + "SELECT DISTINCT metadata->>'visibility', metadata->>'visibility' FROM chunk_embedding "
                + "WHERE metadata->>'visibility' IS NOT NULL AND metadata->>'visibility' <> 'all' "
                + "ON CONFLICT (code) DO NOTHING");
    }
}
