package com.example.kb.dto;

/**
 * POST /api/knowledge 入参：在线撰写知识条目并入库。
 */
public class KnowledgeEntryCreateRequest {

    private String title;
    private String content;
    /** 可选：分类（单值） */
    private String category;
    /** 可选：标签，逗号分隔 */
    private String tags;
    /** 可选：可见性，不传为 all 全员可见 */
    private String visibility;

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getTags() {
        return tags;
    }

    public void setTags(String tags) {
        this.tags = tags;
    }

    public String getVisibility() {
        return visibility;
    }

    public void setVisibility(String visibility) {
        this.visibility = visibility;
    }
}
