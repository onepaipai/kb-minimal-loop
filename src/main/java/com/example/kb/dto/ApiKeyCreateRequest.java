package com.example.kb.dto;

/** 建 API Key 的入参：名字只用来在列表里认出它是哪个 Key。 */
public record ApiKeyCreateRequest(String name) {
}
