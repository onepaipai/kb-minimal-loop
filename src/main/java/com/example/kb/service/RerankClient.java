package com.example.kb.service;

import java.util.List;

/** 重排模型客户端：给候选文档按与问题的相关度重新打分排序。失败时抛异常，由调用方回退。 */
public interface RerankClient {

    /** 重排后的一条结果：index 是原 documents 下标，score 是相关度（0-1，越大越相关）。 */
    record RerankedDoc(int index, double score) {
    }

    List<RerankedDoc> rerank(String query, List<String> documents, int topN);
}
