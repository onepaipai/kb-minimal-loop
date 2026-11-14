package com.example.kb.dto;

/**
 * 单条引用命中详情：编号、相关度分数（0~1）、来源文件与命中原文。
 */
public class ReferenceHit {

    private int index;
    private double score;
    private String source;
    private String text;

    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
    }

    public double getScore() {
        return score;
    }

    public void setScore(double score) {
        this.score = score;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }
}
