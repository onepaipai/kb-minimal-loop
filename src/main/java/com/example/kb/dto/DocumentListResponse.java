package com.example.kb.dto;

import java.util.List;

/**
 * GET /api/documents 出参：已入库文档清单。
 */
public class DocumentListResponse {

    private List<DocumentSummary> documents;
    /** 符合当前部门过滤的文档总数（不含分页截断） */
    private int total;
    /** 当前页码，从 0 起 */
    private int page;
    /** 每页条数 */
    private int size;

    public List<DocumentSummary> getDocuments() {
        return documents;
    }

    public void setDocuments(List<DocumentSummary> documents) {
        this.documents = documents;
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
