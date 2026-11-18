package com.example.kb.dto;

/** 建 API Key 的入参：名字只用来在列表里认出它是哪个 Key；scope 为 ask（只能问答与看清单）或 all（默认，除管理接口外都能调）；expiresInDays 为空表示永不过期。 */
public record ApiKeyCreateRequest(String name, String scope, Integer expiresInDays) {
}
