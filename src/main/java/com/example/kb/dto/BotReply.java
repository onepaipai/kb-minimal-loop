package com.example.kb.dto;

/**
 * POST /api/bot/ask 出参（IM 格式）：platform 为 dingtalk/wecom 时返回 {"msgtype":"text","text":{"content":答案}}。
 */
public class BotReply {

    private String msgtype;
    private TextContent text;

    public static BotReply ofText(String content) {
        BotReply reply = new BotReply();
        reply.setMsgtype("text");
        TextContent textContent = new TextContent();
        textContent.setContent(content);
        reply.setText(textContent);
        return reply;
    }

    public String getMsgtype() {
        return msgtype;
    }

    public void setMsgtype(String msgtype) {
        this.msgtype = msgtype;
    }

    public TextContent getText() {
        return text;
    }

    public void setText(TextContent text) {
        this.text = text;
    }

    public static class TextContent {

        private String content;

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }
    }
}
