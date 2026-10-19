package com.ibm.bankofz.ims.jmp;

import com.ibm.bankofz.bridge.runtime.Binary;
import com.ibm.bankofz.bridge.runtime.Ebcdic;
import com.ibm.bankofz.bridge.runtime.PackedDecimal;
import com.ibm.bankofz.bridge.runtime.Text;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class IbtranTest {
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-19T17:34:05.127Z"), ZoneOffset.UTC);

    static byte[] account(long id, String bal, long lasttx) {
        byte[] s = new byte[25];
        Binary.encode(BigDecimal.valueOf(id), s, 0, 18, 0, true, true);
        Text.encode("S", s, 8, 1, Ebcdic.CP1047);
        PackedDecimal.encode(new BigDecimal(bal), s, 9, 15, 2, true);
        Binary.encode(BigDecimal.valueOf(lasttx), s, 17, 18, 0, true, true);
        return s;
    }

    static byte[] custaccs(long cust, long acc) {
        byte[] s = new byte[16];
        Binary.encode(BigDecimal.valueOf(cust), s, 0, 9, 0, true, true);
        Binary.encode(BigDecimal.valueOf(acc), s, 4, 18, 0, true, true);
        Binary.encode(BigDecimal.ONE, s, 12, 9, 0, true, true);
        return s;
    }

    static byte[] request(String trx) {
        byte[] m = new byte[56];
        Binary.encode(BigDecimal.valueOf(56), m, 0, 4, 0, false, false);
        Text.encode("IBTRAN", m, 4, 8, Ebcdic.CP1047);
        Text.encode("000000000000000001", m, 12, 18, Ebcdic.CP1047);
        Text.encode("0000000000025.50", m, 30, 16, Ebcdic.CP1047);
        Text.encode(trx, m, 46, 1, Ebcdic.CP1047);
        Text.encode("000000009", m, 47, 9, Ebcdic.CP1047);
        return m;
    }

    final List<byte[]> db2 = new ArrayList<>();

    FakeDli run(boolean strict, String forcedHist, byte[]... msgs) throws Exception {
        FakeDli dli = new FakeDli().segment("ACCOUNT", account(1, "100.00", 5)).segment("CUSTACCS", custaccs(9, 1));
        for (byte[] m : msgs) {
            dli.message(m);
        }
        if (forcedHist != null) {
            dli.forcedInsert.put("HISTORY", forcedHist);
        }
        new Ibtran(dli, CustomerProgramsTest.NULL, db2::add, CLOCK, strict).run();
        return dli;
    }

    @Test
    void withdrawalWritesDb2ThenImsHistoryAndRepliesWithTheSummary() throws Exception {
        FakeDli d = run(false, null, request("w"));
        assertArrayEquals(account(1, "74.50", 6), d.db.get("ACCOUNT").get(0));
        byte[] hist = d.db.get("HISTORY").get(0);
        assertArrayEquals(hist, db2.get(0));
        assertEquals(new BigDecimal("10000000006"), Binary.decode(hist, 0, 18, 0, true));
        assertEquals("2026-10-19 17:34:05.120", CustomerProgramsTest.text(hist, 8, 23));
        assertEquals("w", CustomerProgramsTest.text(hist, 31, 1));
        assertEquals(new BigDecimal("25.50"), PackedDecimal.decode(hist, 32, 15, 2));
        assertEquals(new BigDecimal("74.50"), PackedDecimal.decode(hist, 56, 15, 2));
        byte[] out = d.output.get(0);
        assertEquals(65, out.length);
        assertEquals("00410000", IbacsumTest.HEX.formatHex(out, 0, 4));
        assertEquals("           74.50" + " ".repeat(27), CustomerProgramsTest.text(out, 4, 43));
        assertEquals("1", CustomerProgramsTest.text(out, 47, 1));
        byte[] acc = account(1, "74.50", 6);
        assertArrayEquals(Arrays.copyOfRange(acc, 9, 17), Arrays.copyOfRange(out, 48, 56));
        assertArrayEquals(Arrays.copyOfRange(acc, 0, 8), Arrays.copyOfRange(out, 57, 65));
    }

    @Test
    void invalidTypeTouchesNothing() throws Exception {
        FakeDli d = run(false, null, request("x"));
        assertEquals(48, d.output.get(0).length);
        assertEquals(Ibtran.INVALID_TRXTYPE,
                CustomerProgramsTest.text(d.output.get(0), 4, 43));
        assertEquals(0, db2.size());
    }

    @Test
    void historyIsrtFailureKeepsLegacyBehaviourByDefault() throws Exception {
        FakeDli d = run(false, "II", request("d"), request("d"));
        assertEquals(1, d.output.size());
        assertArrayEquals(account(1, "125.50", 6), d.db.get("ACCOUNT").get(0));
        assertEquals(1, db2.size());
        assertEquals(0, d.rollbacks);
    }

    @Test
    void strictModeRollsBackInstead() throws Exception {
        FakeDli d = run(true, "II", request("d"));
        assertEquals(1, d.rollbacks);
        assertArrayEquals(account(1, "100.00", 5), d.db.get("ACCOUNT").get(0));
        assertEquals("BAD STATUS CODE WAS RECEIVED: II", CustomerProgramsTest.text(d.output.get(0), 4, 43).strip());
    }

    @Test
    void zonedBalanceDropsTheSign() {
        assertEquals("             .00", Ibtran.zoned(BigDecimal.ZERO.setScale(2)));
        assertEquals("           12.30", Ibtran.zoned(new BigDecimal("-12.30")));
    }
}
