package com.example.kb.service;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 引用校验（纯函数）：sanitizeCitations 只管编号超范围，这里再管编号有没有文本依据。
 * 引用它的那句话与第 n 块文本共享至少一个有效词（长度≥2，纯标点不算），否则删掉该编号；
 * 只删编号不删正文；答案本来就没有引用时原样返回，不编造、不追加。
 */
public final class CitationValidator {

    private static final Pattern CITATION = Pattern.compile("\\[(\\d+)\\]");
    private static final Set<String> STOP_WORDS = Set.of("可以", "不能", "需要", "如果", "因为", "所以", "我们", "你们",
            "系统", "用户", "人员", "相关", "资料", "内容", "问题", "进行", "这个", "那个", "以及", "或者", "是否");

    private CitationValidator() {
    }

    /** 校验答案里的每个 [n]：超范围的删，没文本依据的删，其余不动。 */
    public static String validate(String answer, List<String> chunkTexts) {
        if (answer == null || chunkTexts == null || chunkTexts.isEmpty()) {
            return answer;
        }
        Matcher matcher = CITATION.matcher(answer);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String digits = matcher.group(1);
            int n = digits.length() > 9 ? -1 : Integer.parseInt(digits);
            String replacement = keepCitation(answer, matcher.start(), matcher.end(), n, chunkTexts)
                    ? matcher.group(0) : "";
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static boolean keepCitation(String answer, int start, int end, int n, List<String> chunkTexts) {
        if (n < 1 || n > chunkTexts.size()) {
            return false;
        }
        Set<String> sentenceWords = words(sentenceAround(answer, start, end));
        if (sentenceWords.isEmpty()) {
            // 这句话本身没有有效词（比如“见 [1]。”），没东西可核对，不删
            return true;
        }
        sentenceWords.retainAll(words(chunkTexts.get(n - 1)));
        return !sentenceWords.isEmpty();
    }

    /** 引用所在的那句话：前后以句末标点（。！？!?；;）或换行为界；逗号不断句，引用归属整句。 */
    private static String sentenceAround(String answer, int start, int end) {
        int from = start;
        while (from > 0 && !isSentenceBoundary(answer.charAt(from - 1))) {
            from--;
        }
        int to = end;
        while (to < answer.length() && !isSentenceBoundary(answer.charAt(to))) {
            to++;
        }
        return answer.substring(from, to);
    }

    private static boolean isSentenceBoundary(char c) {
        return c == '。' || c == '！' || c == '？' || c == '!' || c == '?' || c == '；' || c == ';' || c == '\n';
    }

    /**
     * 有效词抽取：按非字母数字切；中文连续汉字按二字滑动取词，英文词至少 2 字母；
     * 纯标点切完自然没有。切法与 HybridSearchService 的分词同源（中文二字、英文下限 2 字母是这里的核对口径）。
     */
    static Set<String> words(String text) {
        Set<String> words = new HashSet<>();
        if (text == null) {
            return words;
        }
        for (String part : text.split("[^\\p{L}\\p{N}]+")) {
            if (part.isEmpty()) {
                continue;
            }
            if (containsHan(part)) {
                addBigrams(words, part);
            } else if (part.length() >= 2) {
                String normalized = part.toLowerCase(Locale.ROOT);
                if (!STOP_WORDS.contains(normalized)) {
                    words.add(normalized);
                }
            }
        }
        words.removeAll(STOP_WORDS);
        return words;
    }

    private static boolean containsHan(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.UnicodeBlock.of(text.charAt(i)) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS) {
                return true;
            }
        }
        return false;
    }

    private static void addBigrams(Set<String> words, String text) {
        for (int i = 0; i + 1 < text.length(); i++) {
            words.add(text.substring(i, i + 2));
        }
    }
}
