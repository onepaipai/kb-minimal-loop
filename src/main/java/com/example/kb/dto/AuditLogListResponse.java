package com.example.kb.dto;

import java.util.List;

import com.example.kb.service.AuditService;

/** GET /api/admin/audit-logs 出参：分页的审计日志。 */
public class AuditLogListResponse {

    private List<AuditService.AuditEntry> logs;
    private int total;
    private int page;
    private int size;

    public List<AuditService.AuditEntry> getLogs() {
        return logs;
    }

    public void setLogs(List<AuditService.AuditEntry> logs) {
        this.logs = logs;
    }

    public int getTotal() {
        return total;
    }

    public void setTotal(int total) {
        this.total = total;
    }

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public int getSize() {
        return size;
    }

    public void setSize(int size) {
        this.size = size;
    }
}
