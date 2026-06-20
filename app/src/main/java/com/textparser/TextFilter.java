package com.textparser;

public final class TextFilter {

    private static final float MIN_LINE_CONFIDENCE = 60f;
    private static final float MIN_READABLE_RATIO = 0.72f;
    private static final float MIN_CYRILLIC_RATIO = 0.45f;

    private TextFilter() {
    }

    public static boolean isUsefulLine(String line, float confidence) {
        if (line == null || confidence < MIN_LINE_CONFIDENCE) {
            return false;
        }

        String trimmed = line.trim();
        if (trimmed.length() < 3) {
            return false;
        }

        int letters = 0;
        int cyrillic = 0;
        int readable = 0;

        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if (Character.isLetter(ch)) {
                letters++;
                readable++;
                if (isCyrillic(ch)) {
                    cyrillic++;
                }
            } else if (Character.isDigit(ch) || Character.isWhitespace(ch) || isAllowedSymbol(ch)) {
                readable++;
            }
        }

        float readableRatio = (float) readable / trimmed.length();
        if (readableRatio < MIN_READABLE_RATIO) {
            return false;
        }

        if (letters == 0) {
            return confidence >= 75f;
        }

        float cyrillicRatio = (float) cyrillic / letters;
        if (cyrillicRatio >= MIN_CYRILLIC_RATIO) {
            return true;
        }

        return confidence >= 80f;
    }

    private static boolean isCyrillic(char ch) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(ch);
        return block == Character.UnicodeBlock.CYRILLIC
                || block == Character.UnicodeBlock.CYRILLIC_SUPPLEMENTARY;
    }

    private static boolean isAllowedSymbol(char ch) {
        return switch (ch) {
            case '.', ',', '!', '?', ';', ':', '(', ')', '-', '"', '\'', '@',
                 '«', '»', '/', '\\', '№', '—', '–', '+' -> true;
            default -> false;
        };
    }
}
