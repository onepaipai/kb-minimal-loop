package com.example.kb.service;

import java.util.List;

import com.example.kb.service.HybridSearchService.RetrievedChunk;

final class HybridSearchSqlBuilder {

    static final int KEYWORD_LIMIT = 20;
    static final int FUZZY_LIMIT = 5;
    static final double FUZZY_SIMILARITY_THRESHOLD = 0.2;

    private HybridSearchSqlBuilder() {
    }

    static void appendPermissionConditions(StringBuilder sql, List<Object> params, String department,
                                           List<String> sources) {
        sql.append("metadata->>'source' IS NOT NULL AND COALESCE(metadata->>'status','approved') = 'approved' AND ");
        if (department == null) {
            sql.append("metadata->>'visibility' = ? AND ");
            params.add("all");
        } else {
            sql.append("metadata->>'visibility' IN (?, ?) AND ");
            params.add("all");
            params.add(department);
        }
        if (sources != null && !sources.isEmpty()) {
            sql.append("metadata->>'source' IN (");
            appendPlaceholders(sql, sources.size());
            sql.append(") AND ");
            params.addAll(sources);
        }
    }

    static void appendPlaceholders(StringBuilder sql, int count) {
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append("?");
        }
    }
}
