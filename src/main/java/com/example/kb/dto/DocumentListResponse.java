package com.example.kb.dto;

import java.util.List;

/**
 * GET /api/documents 出参：已入库文档清单。
 */
public class DocumentListResponse {

    private List<DocumentSummary> documents;

    public List<DocumentSummary> getDocuments() {
        return documents;
    }

    public void setDocuments(List<DocumentSummary> documents) {
        this.documents = documents;
    }
}
