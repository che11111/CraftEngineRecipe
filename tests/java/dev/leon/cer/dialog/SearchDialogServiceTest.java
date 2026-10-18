package dev.leon.cer.dialog;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SearchDialogServiceTest {
    @Test
    void acceptsTheExactLimitAndRejectsAnOversizedRawResponseBeforeTrimming() {
        assertEquals("abcd", SearchDialogService.validatedKeyword("abcd", 4));
        assertEquals("", SearchDialogService.validatedKeyword("abcde", 4));
        assertEquals("", SearchDialogService.validatedKeyword(" abcd ", 4));
        assertEquals("abc", SearchDialogService.validatedKeyword(" abc ", 5));
    }

    @Test
    void unicodeUsesTheSameUtf16LimitAsTheDialogAndEmptyInputsReturnToMenu() {
        assertEquals("茄子", SearchDialogService.validatedKeyword("茄子", 2));
        assertEquals("😀", SearchDialogService.validatedKeyword("😀", 2));
        assertEquals("", SearchDialogService.validatedKeyword("😀", 1));
        assertEquals("", SearchDialogService.validatedKeyword(null, 32));
        assertEquals("", SearchDialogService.validatedKeyword(" \t", 32));
    }
}
