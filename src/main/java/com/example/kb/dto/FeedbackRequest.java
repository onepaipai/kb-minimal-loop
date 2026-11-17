package com.example.kb.dto;

/** POST /api/feedback 入参：原问答 + 好评/差评。 */
public class FeedbackRequest {

    private String question;
    private String answer;
    private Boolean helpful;

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public Boolean getHelpful() {
        return helpful;
    }

    public void setHelpful(Boolean helpful) {
        this.helpful = helpful;
    }
}
