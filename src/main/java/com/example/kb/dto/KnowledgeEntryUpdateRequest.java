package com.example.kb.dto;

/**
 * PUT /api/knowledge/{id} 入参：更新知识条目，字段为 null 的沿用原值；标题不可改（标题即来源，改名等于新建）。
 */
public class KnowledgeEntryUpdateRequest {

    private String content;
    private String category;
    private String tags;
    private String visibility;

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
