package com.example.kb.service;

import java.util.List;

import com.example.kb.dto.ReferenceHit;

final class RagAnswerHelper {

    static String sanitizeCitations(String answer, int hitCount) {
        if (answer == null) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\[(\\d+)\\]").matcher(answer);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            int n = Integer.parseInt(matcher.group(1));
            String replacement = n >= 1 && n <= hitCount ? matcher.group(0) : "";
            matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /** 引用校验用的块文本（按引用编号顺序）：第 n 块对应 [n]。 */
    static List<String> chunkTexts(List<ReferenceHit> hits) {
        return hits.stream().map(ReferenceHit::getText).toList();
    }
}
