package com.example.kb.service;

/** pgvector 块元数据的键，Java 代码里只认这里。 */
final class MetadataKeys {

    static final String SOURCE = "source";
    static final String VISIBILITY = "visibility";
    /** 同一次入库的批次号，同名替换时只删非本批次的旧块 */
    static final String BATCH = "batch";

    private MetadataKeys() {
    }
}
