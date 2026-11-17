package com.example.kb.service;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** 启动时把已入库块里出现过的部门代号登记进部门表（老数据回填，幂等）。 */
@Component
public class DepartmentBootstrap {

    private final DepartmentService departmentService;

    public DepartmentBootstrap(DepartmentService departmentService) {
        this.departmentService = departmentService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seedDepartments() {
        departmentService.seedFromChunks();
    }
}
