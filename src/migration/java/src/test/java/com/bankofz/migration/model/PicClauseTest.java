package com.bankofz.migration.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PicClauseTest {

    @Test
    void parsesAlphanumeric() {
        PicClause p = PicClause.parse("X(50)");
        assertTrue(p.alphanumeric());
        assertEquals(50, p.length());
        assertEquals(50, p.storageBytes(Usage.DISPLAY));
    }

    @Test
    void parsesZonedDecimal() {
        PicClause p = PicClause.parse("S9(10)V99");
        assertTrue(p.signed());
        assertEquals(10, p.integerDigits());
        assertEquals(2, p.scale());
        assertEquals(12, p.storageBytes(Usage.DISPLAY));
        assertEquals(new BigDecimal("9999999999.99"), p.maxValue());
    }

    @Test
    void parsesPackedAndBinary() {
        PicClause amount = PicClause.parse("S9(13)V9(2)");
        assertEquals(15, amount.length());
        assertEquals(8, amount.storageBytes(Usage.COMP_3)); // DBD BALANCE/AMOUNT BYTES=8
        assertEquals(8, PicClause.parse("S9(18)").storageBytes(Usage.COMP_5)); // DBD TXID BYTES=8
        assertEquals(2, PicClause.parse("9(04)").storageBytes(Usage.COMP));
    }

    @Test
    void repeatCountsAreEquivalentToRepeatedSymbols() {
        assertTrue(PicClause.parse("999").sameShape(PicClause.parse("9(3)")));
        assertTrue(PicClause.parse("9(4)V99").sameShape(PicClause.parse("9999V9(2)")));
        assertFalse(PicClause.parse("9(4)V99").sameShape(PicClause.parse("S9(4)V99")));
    }

    @Test
    void rejectsUnsupportedPictures() {
        assertThrows(IllegalArgumentException.class, () -> PicClause.parse("Z(5)9"));
        assertThrows(IllegalArgumentException.class, () -> PicClause.parse("9S9"));
        assertThrows(IllegalArgumentException.class, () -> PicClause.parse("X9"));
    }
}
