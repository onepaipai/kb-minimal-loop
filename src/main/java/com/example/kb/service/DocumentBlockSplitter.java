package com.example.kb.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DocumentBlockSplitter {

    static final Pattern FENCE = Pattern.compile("^\\s*```.*$");
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$");

    List<Block> splitIntoBlocks(String text) {
        String[] lines = text.split("\\n", -1);
        List<Block> blocks = new ArrayList<>();
        String[] headings = new String[7];
        StringBuilder paragraph = new StringBuilder();
        String paragraphSection = "";
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                flushParagraph(blocks, paragraph, paragraphSection);
                int level = heading.group(1).length();
                headings[level] = heading.group(2);
                clearDeeper(headings, level);
                paragraphSection = sectionOf(headings);
                paragraph.append(line);
                continue;
            }
            if (FENCE.matcher(line).matches()) {
                flushParagraph(blocks, paragraph, paragraphSection);
                i = addFenceBlock(blocks, lines, i, sectionOf(headings));
                continue;
            }
            if (isTableLine(line)) {
                flushParagraph(blocks, paragraph, paragraphSection);
                i = addTableBlock(blocks, lines, i, sectionOf(headings));
                continue;
            }
            if (line.isBlank()) {
                flushParagraph(blocks, paragraph, paragraphSection);
                continue;
            }
            if (paragraph.length() == 0) {
                paragraphSection = sectionOf(headings);
            } else {
                paragraph.append("\n");
            }
            paragraph.append(line);
        }
        flushParagraph(blocks, paragraph, paragraphSection);
        return blocks;
    }

    private void flushParagraph(List<Block> blocks, StringBuilder paragraph, String section) {
        if (paragraph.length() == 0) {
            return;
        }
        blocks.add(new Block(paragraph.toString(), section, false));
        paragraph.setLength(0);
    }

    private void clearDeeper(String[] headings, int level) {
        for (int i = level + 1; i < headings.length; i++) {
            headings[i] = null;
        }
    }

    private String sectionOf(String[] headings) {
        StringBuilder section = new StringBuilder();
        for (int i = 1; i < headings.length; i++) {
            if (headings[i] == null) {
                continue;
            }
            if (section.length() > 0) {
                section.append(" > ");
            }
            section.append(headings[i]);
        }
        return section.toString();
    }

    private boolean isTableLine(String line) {
        return line.stripLeading().startsWith("|");
    }

    /** 从第 i 行起收一整个代码围栏（含首尾围栏行），返回围栏结束行的下标。 */
    private int addFenceBlock(List<Block> blocks, String[] lines, int i, String section) {
        StringBuilder fence = new StringBuilder(lines[i]);
        i++;
        while (i < lines.length &&!FENCE.matcher(lines[i]).matches()) {
            fence.append("\n").append(lines[i]);
            i++;
        }
        if (i < lines.length) {
            fence.append("\n").append(lines[i]);
        }
        blocks.add(new Block(fence.toString(), section, true));
        return i;
    }

    /** 从第 i 行起收连续的表格行，返回最后一行表格的下标。 */
    private int addTableBlock(List<Block> blocks, String[] lines, int i, String section) {
        StringBuilder table = new StringBuilder(lines[i]);
        i++;
        while (i < lines.length && isTableLine(lines[i])) {
            table.append("\n").append(lines[i]);
            i++;
        }
        blocks.add(new Block(table.toString(), section, true));
        return i - 1;
    }
}

record Block(String text, String section, boolean atomic) {
}
