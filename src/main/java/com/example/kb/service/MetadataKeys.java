package com.example.kb.service;

/** pgvector 块元数据的键，Java 代码里只认这里。 */
final class MetadataKeys {

    static final String SOURCE = "source";
    static final String VISIBILITY = "visibility";
    /** 同一次入库的批次号，同名替换时只标非本批次的旧版块 */
    static final String BATCH = "batch";
    /** 审批状态：pending 待审批、approved 已发布（问答只查它）、rejected 已驳回、superseded 被新版替换（问答与清单都排除） */
    static final String STATUS = "status";
    /** 被替换之前的状态：回滚时按它恢复，没批过的旧版回滚后还是待审批 */
    static final String PREV_STATUS = "prev_status";
    /** 文档版本号：同名替换时 +1，从 1 起 */
    static final String VERSION = "version";
    /** 整个文件内容的 SHA-256：同名同内容再次上传时直接跳过，不重新向量化 */
    static final String FILE_HASH = "file_hash";
    /** 切块器写的块元数据：块号（0 起）、块总数、章节路径（标题栈，如“请假制度 > 年假”） */
    static final String CHUNK_INDEX = "chunk_index";
    static final String CHUNK_TOTAL = "chunk_total";
    static final String SECTION = "section";
    /** 知识条目的分类（单值）与标签（逗号分隔多值）：条目入库成功后回填到当前版块的元数据上 */
    static final String CATEGORY = "category";
    static final String TAGS = "tags";

    private MetadataKeys() {
    }
}
