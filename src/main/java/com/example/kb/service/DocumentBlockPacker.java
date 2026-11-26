package com.example.kb.service;

import java.util.ArrayList;
import java.util.List;

final class DocumentBlockPacker {

    private static final int MIN_OVERLAP_CHARS = 20;

    private final int maxChars;
    private final int overlapChars;

    DocumentBlockPacker(int maxChars, int overlapChars) {
        this.maxChars = maxChars;
        this.overlapChars = overlapChars;
    }

    List<Block> toPieces(List<Block> blocks) {
        List<Block> pieces = new ArrayList<>();
        for (Block block: blocks) {
            if (block.text().length() <= maxChars) {
                pieces.add(block);
            } else if (block.atomic() && isTableLine(block.text())) {
                splitTable(block, pieces);
            } else if (block.atomic()) {
                splitFence(block, pieces);
            } else {
                splitParagraph(block, pieces);
            }
        }
        return pieces;
    }

    private void splitParagraph(Block block, List<Block> pieces) {
        StringBuilder current = new StringBuilder();
        for (String sentence: sentences(block.text())) {
            if (sentence.length() > maxChars) {
                flushPiece(pieces, current, block.section());
                cutHard(sentence, block.section(), pieces);
                continue;
            }
            if (current.length() > 0 && current.length() + sentence.length() > maxChars) {
                flushPiece(pieces, current, block.section());
            }
            current.append(sentence);
        }
        flushPiece(pieces, current, block.section());
    }

    private void flushPiece(List<Block> pieces, StringBuilder current, String section) {
        if (current.length() == 0) {
            return;
        }
        pieces.add(new Block(current.toString(), section, false));
        current.setLength(0);
    }

    /** 单句超长时按字符硬切（无标点可断的长串，如 URL 或长数字串）。 */
    private void cutHard(String text, String section, List<Block> pieces) {
        for (int from = 0; from < text.length(); from += maxChars) {
            pieces.add(new Block(text.substring(from, Math.min(text.length(), from + maxChars)), section, false));
        }
    }

    /** 表格按行分组：表头两行（标题 + 分隔行）每组都带，行本身超长才硬切。 */
    private void splitTable(Block block, List<Block> pieces) {
        String[] rows = block.text().split("\n", -1);
        String header = rows[0];
        if (rows.length > 1 && rows[1].strip().startsWith("|") && rows[1].contains("-")) {
            header += "\n" + rows[1];
        }
        int firstDataRow = header.contains("\n")? 2: 1;
        StringBuilder current = new StringBuilder(header);
        for (int i = firstDataRow; i < rows.length; i++) {
            if (rows[i].length() > maxChars) {
                flushPiece(pieces, current, block.section());
                cutHard(rows[i], block.section(), pieces);
                current = new StringBuilder(header);
                continue;
            }
            if (current.length() + 1 + rows[i].length() > maxChars) {
                flushPiece(pieces, current, block.section());
                current = new StringBuilder(header);
            }
            current.append("\n").append(rows[i]);
        }
        flushPiece(pieces, current, block.section());
    }

    /** 代码围栏按行分组：每组首尾都补围栏行，组内仍是原文行。 */
    private void splitFence(Block block, List<Block> pieces) {
        String[] rows = block.text().split("\n", -1);
        String open = rows[0];
        boolean closed = rows.length > 1 && DocumentBlockSplitter.FENCE.matcher(rows[rows.length - 1]).matches();
        int lastBody = closed? rows.length - 1: rows.length;
        StringBuilder current = new StringBuilder(open);
        for (int i = 1; i < lastBody; i++) {
            if (current.length() + 1 + rows[i].length() + 4 > maxChars) {
                pieces.add(new Block(current + "\n```", block.section(), false));
                current = new StringBuilder(open);
            }
            current.append("\n").append(rows[i]);
        }
        pieces.add(new Block(current + (closed? "\n```": ""), block.section(), false));
    }

    /** 片段贪心装块：装满即封块，下一块带上上一块句末结尾的重叠尾巴（放不下就不要）。 */
    List<Block> pack(List<Block> pieces) {
        List<Block> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String currentSection = "";
        String pendingTail = "";
        for (Block piece: pieces) {
            if (current.length() > 0 && current.length() + 2 + piece.text().length() > maxChars) {
                chunks.add(new Block(current.toString(), currentSection, false));
                pendingTail = overlapTail(current.toString());
                current = new StringBuilder();
            }
            if (current.length() == 0) {
                currentSection = piece.section();
                if (!pendingTail.isEmpty() && pendingTail.length() + 2 + piece.text().length() <= maxChars) {
                    current.append(pendingTail).append("\n\n");
                }
                pendingTail = "";
            } else {
                current.append("\n\n");
            }
            current.append(piece.text());
        }
        if (current.length() > 0) {
            chunks.add(new Block(current.toString(), currentSection, false));
        }
        return chunks;
    }

    /** 重叠尾巴：取末尾 overlapChars，从第一个完整句开始，太短就不要。 */
    private String overlapTail(String body) {
        String window = body.substring(Math.max(0, body.length() - overlapChars));
        int firstEnd = firstSentenceEnd(window);
        if (firstEnd < 0) {
            return "";
        }
        String tail = window.substring(firstEnd + 1).stripLeading();
        return tail.length() >= MIN_OVERLAP_CHARS? tail: "";
    }

    private int firstSentenceEnd(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (isSentenceEnd(text, i)) {
                return i;
            }
        }
        return -1;
    }

    /** 按句切：中文句末标点、换行、英文句末标点加空格都算句界。 */
    private List<String> sentences(String text) {
        List<String> result = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (isSentenceEnd(text, i)) {
                result.add(text.substring(start, i + 1));
                start = i + 1;
            }
        }
        if (start < text.length()) {
            result.add(text.substring(start));
        }
        return result;
    }

    private boolean isSentenceEnd(String text, int i) {
        char c = text.charAt(i);
        if (c == '。' || c == '！' || c == '？' || c == '；' || c == '\n') {
            return true;
        }
        if ((c == '.' || c == '!' || c == '?') && (i + 1 == text.length() || text.charAt(i + 1) == ' ')) {
            return true;
        }
        return false;
    }

    private boolean isTableLine(String line) {
        return line.stripLeading().startsWith("|");
    }
}
