package com.example.kb.controller;

import java.util.List;

import com.example.kb.dto.DepartmentRequest;
import com.example.kb.service.DepartmentService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 部门管理接口：查部门清单、新建部门、删除部门；仅管理员。 */
@RestController
@RequestMapping("/api/admin/departments")
@PreAuthorize("hasRole('ADMIN')")
public class DepartmentController {

    private final DepartmentService departmentService;

    public DepartmentController(DepartmentService departmentService) {
        this.departmentService = departmentService;
    }

    /** 部门清单：代号、名称、父部门代号。 */
    @GetMapping
    public List<DepartmentService.Department> listDepartments() {
        return departmentService.listDepartments();
    }

    /** 新建部门：code 为代号（小写字母数字下划线中划线），parentCode 可空。 */
    @PostMapping
    public DepartmentService.Department createDepartment(@RequestBody DepartmentRequest request) {
        return departmentService.createDepartment(request.getCode(), request.getName(), request.getParentCode());
    }

    /** 删除部门：仍有文档挂在这个可见性下时报 409。 */
    @DeleteMapping
    public void deleteDepartment(@RequestParam("code") String code) {
        departmentService.deleteDepartment(code);
    }
}
