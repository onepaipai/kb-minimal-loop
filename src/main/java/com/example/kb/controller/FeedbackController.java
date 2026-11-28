package com.example.kb.controller;

import com.example.kb.common.RequestValidation;
import com.example.kb.dto.FeedbackRequest;
import com.example.kb.security.DepartmentScope;
import com.example.kb.security.UserPrincipal;
import com.example.kb.service.FeedbackService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 问答反馈接口：用户对回答点好评/差评。 */
@RestController
@RequestMapping("/api")
public class FeedbackController {

    private final FeedbackService feedbackService;

    public FeedbackController(FeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    /** 提交反馈：helpful 为 true 好评、false 差评；问答原文截断存 500 字。 */
    @PostMapping("/feedback")
    public void submitFeedback(@RequestBody FeedbackRequest request) {
        if (request == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "请求体不能为空");
        }
        RequestValidation.validateFeedback(request.getQuestion(), request.getAnswer(), request.getHelpful());
        UserPrincipal user = DepartmentScope.requireCurrentUser();
        feedbackService.submit(user.username(), request.getQuestion(), request.getAnswer(),
                request.getHelpful() != null && request.getHelpful());
    }
}
