package com.example.kb.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 流式引用过滤器：边收 token 边删超范围的引用编号，和整段版 sanitizeCitations 同一条规则。
 * 模型可能把 "[9]" 拆成 "[", "9", "]" 三个 token 发过来，所以以 "[" 开头的未闭合片段先攒着，
 * 等下一个 token 再定；流结束时 flush() 把攒着的片段按原样放出来（没闭合的 "[1" 本来就不是引用）。
 */
public class StreamingCitationFilter {

    private static final Pattern CITATION = Pattern.compile("\\[(\\d+)\\]");
    /** 尾部可能成引用的片段：一个 "[" 后面只跟数字（或什么都没有），还没见到 "]" */
    private static final Pattern OPEN_CITATION_TAIL = Pattern.compile("\\[\\d*$");

    private final int hitCount;
    private final StringBuilder pending = new StringBuilder();

    public StreamingCitationFilter(int hitCount) {
        this.hitCount = hitCount;
    }

    /** 喂一个 token，返回现在能安全发出去的文本（可能为空串：整个 token 是被删的引用）。 */
    public String feed(String token) {
        if (token == null || token.isEmpty()) {
            return "";
        }
        pending.append(token);
        return drain(false);
    }

    /** 流结束：把攒着的尾巴按原样放出来（未闭合的不是引用，整段版也不会删它）。 */
    public String flush() {
        return drain(true);
    }

    private String drain(boolean finalDrain) {
        String text = pending.toString();
        pending.setLength(0);
        if (text.isEmpty()) {
            return "";
        }
        int holdFrom = -1;
        if (!finalDrain) {
            Matcher tail = OPEN_CITATION_TAIL.matcher(text);
            if (tail.find()) {
                holdFrom = tail.start();
            }
        }
        String processable = holdFrom >= 0 ? text.substring(0, holdFrom) : text;
        String held = holdFrom >= 0 ? text.substring(holdFrom) : "";
        pending.append(held);
        return dropOutOfRange(processable);
    }

    /** 删掉超出 [1] 到 [hitCount] 的编号，范围内的原样保留（与 RagService.sanitizeCitations 同规则）。 */
    private String dropOutOfRange(String text) {
        Matcher matcher = CITATION.matcher(text);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            int n = Integer.parseInt(matcher.group(1));
            String replacement = n >= 1 && n <= hitCount ? matcher.group(0) : "";
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
