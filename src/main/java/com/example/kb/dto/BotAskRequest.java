package com.example.kb.dto;

/**
 * POST /api/bot/ask 入参：通用 {"question":"…"}，或钉钉/企微文本消息体 {"msgtype":"text","text":{"content":"…"}}；
 * platform 传 dingtalk 或 wecom 时按 IM 回复格式返回。
 */
public class BotAskRequest {

    private String question;
    private String platform;
    private TextPayload text;

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getPlatform() {
        return platform;
    }

    public void setPlatform(String platform) {
        this.platform = platform;
    }

    public TextPayload getText() {
        return text;
    }

    public void setText(TextPayload text) {
        this.text = text;
    }

    /** 钉钉/企微文本消息的消息体：{"content":"问题"} */
    public static class TextPayload {

        private String content;

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }
    }
}
