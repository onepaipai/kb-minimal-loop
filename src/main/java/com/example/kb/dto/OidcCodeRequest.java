package com.example.kb.dto;

/** 前端换 OIDC 登录码：回调地址里带的 5 分钟一次性码。 */
public class OidcCodeRequest {

    private String code;

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }
}
