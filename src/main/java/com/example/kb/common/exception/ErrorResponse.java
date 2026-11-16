package com.example.kb.common.exception;

import java.time.Instant;

/** 统一错误体：不含堆栈。 */
public record ErrorResponse(Instant timestamp, int status, String message, String path) {

    public static ErrorResponse of(int status, String message, String path) {
        return new ErrorResponse(Instant.now(), status, message, path);
    }
}
