package com.example.kb.service;

/** pgvector 块元数据的键，Java 代码里只认这里。 */
final class MetadataKeys {

    static final String SOURCE = "source";
    static final String VISIBILITY = "visibility";
    /** 同一次入库的批次号，同名替换时只删非本批次的旧块 */
    static final String BATCH = "batch";
    /** 审批状态：pending 待审批、approved 已发布（问答只查它）、rejected 已驳回 */
    static final String STATUS = "status";
    /** 文档版本号：同名替换时 +1，从 1 起 */
    static final String VERSION = "version";
    /** 切块器写的块元数据：块号（0 起）、块总数、章节路径（标题栈，如“请假制度 > 年假”） */
    static final String CHUNK_INDEX = "chunk_index";
    static final String CHUNK_TOTAL = "chunk_total";
    static final String SECTION = "section";

    private MetadataKeys() {
    }
}
