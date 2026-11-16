package com.example.kb.common;

import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 可见性 / 部门代号：全员可见为 all，其余只允许小写字母、数字、下划线、中划线。 */
public final class Visibility {

    public static final String ALL = "all";

    private static final Pattern CODE_PATTERN = Pattern.compile("[a-z0-9_-]+");

    private Visibility() {
    }

    /** 统一转小写后校验格式，不合规报 400；null 由调用方先处理。 */
    public static String normalize(String raw) {
        String value = raw.toLowerCase(Locale.ROOT);
        if (!CODE_PATTERN.matcher(value).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "visibility / department 只允许小写字母、数字、下划线、中划线");
        }
        return value;
    }
}
