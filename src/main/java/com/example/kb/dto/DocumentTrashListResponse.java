package com.example.kb.dto;

import java.util.ArrayList;
import java.util.List;

/** 回收站分页结果。 */
public class DocumentTrashListResponse {

    private List<DocumentTrashSummary> documents = new ArrayList<>();
    private int total;
    private int page;
    private int size;

    public List<DocumentTrashSummary> getDocuments() {
        return documents;
    }

    public void setDocuments(List<DocumentTrashSummary> documents) {
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
