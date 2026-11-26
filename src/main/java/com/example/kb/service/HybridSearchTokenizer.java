package com.example.kb.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class HybridSearchTokenizer {

    private static final Logger log = LoggerFactory.getLogger(HybridSearchService.class);
    private static final int MAX_TOKENS = 8;
    private static final Map<String, List<String>> SYNONYMS = loadSynonyms();

    private HybridSearchTokenizer() {
    }

    static List<String> tokenize(String question) {
        List<String> tokens = splitWords(question, 3, MAX_TOKENS);
        expandSynonyms(tokens);
        return tokens;
    }

    static List<String> splitWords(String text, int minLatinLength, int limit) {
        List<String> words = new ArrayList<>();
        if (text == null) {
            return words;
        }
        for (String part : text.split("[^\\p{L}\\p{N}]+")) {
            if (part.isEmpty()) {
                continue;
            }
            if (containsHan(part)) {
                addBigrams(words, part, limit);
            } else if (part.length() >= minLatinLength) {
                addToken(words, part);
            }
            if (words.size() >= limit) {
                break;
            }
        }
        return words;
    }

    private static void expandSynonyms(List<String> tokens) {
        List<String> additions = new ArrayList<>();
        for (String token : tokens) {
            additions.addAll(SYNONYMS.getOrDefault(token, List.of()));
        }
        for (String synonym : additions) {
            if (tokens.size() >= MAX_TOKENS) {
                return;
            }
            addToken(tokens, synonym);
        }
    }

    static Map<String, List<String>> loadSynonyms() {
        Map<String, List<String>> synonyms = new LinkedHashMap<>();
        try (InputStream in = HybridSearchService.class.getResourceAsStream("/synonyms.txt")) {
            if (in == null) {
                log.warn("同义词表 synonyms.txt 不存在，分词不扩展");
                return synonyms;
            }
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                parseSynonymLine(line, synonyms);
            }
        } catch (IOException e) {
            log.warn("同义词表加载失败，分词不扩展：{}", e.toString());
        }
        return synonyms;
    }

    private static void parseSynonymLine(String line, Map<String, List<String>> synonyms) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return;
        }
        String[] sides = trimmed.split("=", 2);
        if (sides.length != 2) {
            return;
        }
        List<String> group = new ArrayList<>();
        group.add(sides[0].trim());
        for (String synonym : sides[1].split("[,，]")) {
            if (!synonym.isBlank()) {
                group.add(synonym.trim());
            }
        }
        if (group.size() < 2) {
            return;
        }
        for (String word : group) {
            List<String> others = new ArrayList<>(group);
            others.remove(word);
            synonyms.put(word, others);
        }
    }

    private static boolean containsHan(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.UnicodeBlock.of(text.charAt(i)) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS) {
                return true;
            }
        }
        return false;
    }

    private static void addBigrams(List<String> tokens, String text, int limit) {
        for (int i = 0; i + 1 < text.length() && tokens.size() < limit; i++) {
            addToken(tokens, text.substring(i, i + 2));
        }
    }

    private static void addToken(List<String> tokens, String token) {
        if (!tokens.contains(token)) {
            tokens.add(token);
        }
    }

    static String escapeLike(String token) {
        return token.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
