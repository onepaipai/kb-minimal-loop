package com.example.kb.common;

import java.util.List;

import com.example.kb.dto.AskRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 接口层共享的输入边界，避免不同入口对同一交易使用不同的长度和数量规则。 */
public final class RequestValidation {

    private static final int MAX_QUESTION_LENGTH = 4000;
    private static final int MAX_SOURCE_COUNT = 50;
    private static final int MAX_SOURCE_LENGTH = 300;
    private static final int MAX_CATEGORY_LENGTH = 100;
    private static final int MAX_TAGS_LENGTH = 1000;
    private static final int MAX_ENTRY_TITLE_LENGTH = 200;
    private static final int MAX_ENTRY_CONTENT_LENGTH = 100_000;

    private RequestValidation() {
    }

    public static void validateAsk(AskRequest request) {
        if (request == null || request.getQuestion() == null || request.getQuestion().isBlank()) {
            throw badRequest("question 不能为空");
        }
        if (request.getQuestion().length() > MAX_QUESTION_LENGTH) {
            throw badRequest("question 最长 4000 字");
        }
        List<String> sources = request.getSources();
        if (sources != null) {
            if (sources.size() > MAX_SOURCE_COUNT) {
                throw badRequest("sources 最多 50 个");
            }
            for (String source : sources) {
                if (source == null || source.isBlank() || source.length() > MAX_SOURCE_LENGTH) {
                    throw badRequest("source 不能为空且最长 300 字");
                }
            }
        }
        if (request.getDepartment() != null && request.getDepartment().length() > 100) {
            throw badRequest("department 最长 100 字");
        }
    }

    public static void validateEntry(String title, String content, String category, String tags) {
        if (title != null && title.length() > MAX_ENTRY_TITLE_LENGTH) {
            throw badRequest("标题最长 200 字");
        }
        if (content != null && content.length() > MAX_ENTRY_CONTENT_LENGTH) {
            throw badRequest("正文最长 100000 字");
        }
        validateOptionalLength(category, MAX_CATEGORY_LENGTH, "category");
        validateOptionalLength(tags, MAX_TAGS_LENGTH, "tags");
    }

    public static void validateFeedback(String question, String answer, Boolean helpful) {
        if (question == null || question.isBlank()) {
            throw badRequest("question 不能为空");
        }
        if (answer == null || answer.isBlank()) {
            throw badRequest("answer 不能为空");
        }
        if (question.length() > 4000 || answer.length() > 8000) {
            throw badRequest("反馈内容超出长度限制");
        }
        if (helpful == null) {
            throw badRequest("helpful 不能为空");
        }
    }

    public static void validateOptionalLength(String value, int maxLength, String field) {
        if (value != null && value.length() > maxLength) {
            throw badRequest(field + " 最长 " + maxLength + " 字");
        }
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
