package com.ibm.bankofz.loaders;

import com.ibm.bankofz.bridge.runtime.Binary;
import com.ibm.bankofz.bridge.runtime.Ebcdic;
import com.ibm.bankofz.bridge.runtime.PackedDecimal;
import com.ibm.bankofz.bridge.runtime.Text;
import com.ibm.bankofz.parity.snapshot.Dbd;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoadersTest {
    static final Path REPO = Path.of(System.getProperty("bankz.repoRoot"));
    static final Map<String, String> DATA = Map.of("LOADACCT", "ACCOUNT", "LOADCUSA", "CUSTACCS",
            "LOADCUST", "CUSTOMER", "LOADHIST", "HISTORY", "LOADTSTA", "TSTAT");

    static List<String> data(String file) throws Exception {
        return Files.readAllLines(REPO.resolve("src/base/ims/LoadData/" + file + ".data"), StandardCharsets.UTF_8);
    }

    @Test
    void segmentLengthsMatchTheUnchangedDbds() throws Exception {
        for (LoaderSpec s : Loaders.ALL.values()) {
            Dbd dbd = Dbd.parse(REPO.resolve("src/base/ims/DBD/" + s.segment() + ".asm"));
            assertEquals(dbd.segment(s.segment()).bytes(), s.segmentLength(), s.program());
            assertTrue(Files.readString(REPO.resolve("src/base/ims/cobol/" + s.program() + ".cbl"))
                    .contains("ADDRESS OF DBPCB" + (List.of("ACCOUNT", "", "CUSTACCS", "CUSTOMER", "", "HISTORY", "TSTAT")
                            .indexOf(s.pcb()) + 1)), s.program() + " PCB position");
        }
    }

    @Test
    void unstringFollowsCobolDelimiterOrderAndKeepsUnreachedReceivers() {
        String[] r = {"x".repeat(5), "y".repeat(3), "z".repeat(4)};
        Unstring.into("12,\"ab\"", Unstring.LOADER_DELIMITERS, r, new int[] {5, 3, 4});
        assertArrayEquals(new String[] {"12   ", "ab ", "zzzz"}, r);
        Unstring.into("\"q\",,7", Unstring.LOADER_DELIMITERS, r, new int[] {5, 3, 4});
        assertArrayEquals(new String[] {"     ", "q  ", "    "}, r);
    }

    @Test
    void numvalAcceptsCobolFormsAndRejectsGarbage() {
        assertEquals(new BigDecimal("-12.50"), Numval.of(" 12.50CR "));
        assertEquals(new BigDecimal("-3"), Numval.of("3-"));
        assertEquals(BigDecimal.ZERO, Numval.of("    "));
        assertEquals(new BigDecimal("0.5"), Numval.of(".5"));
        assertThrows(NumberFormatException.class, () -> Numval.of("12a"));
    }

    @Test
    void accountSegmentIsByteExact() {
        byte[] seg = Loaders.LOADACCT.build("101,\"c\",8830.00,1", Loaders.LOADACCT.initialState());
        assertEquals("0000000000000065" + "83" + "000000000883000C" + "0000000000000001",
                HexFormat.of().withUpperCase().formatHex(seg));
    }

    @Test
    void cusaReadsAccidFirstAsTheCobolDoes() {
        byte[] seg = Loaders.LOADCUSA.build("101,1,1", Loaders.LOADCUSA.initialState());
        assertEquals(BigDecimal.ONE, Binary.decode(seg, 0, 9, 0, true));
        assertEquals(BigDecimal.valueOf(101), Binary.decode(seg, 4, 18, 0, true));
    }

    @Test
    void historyAmountIsCutByTheX16ReceiverThenHighOrderTruncatedLikeCompute() {
        byte[] seg = Loaders.LOADHIST.build("1,\"t\",\"d\",12345678901234.56,0,1", Loaders.LOADHIST.initialState());
        assertEquals(new BigDecimal("2345678901234.50"), PackedDecimal.decode(seg, 32, 15, 2));
    }

    @Test
    void everyShippedInputRecordLoadsAndDecodesBack() throws Exception {
        for (Map.Entry<String, String> e : DATA.entrySet()) {
            LoaderSpec s = Loaders.ALL.get(e.getKey());
            List<String> in = data(e.getValue());
            ByteArrayOutputStream unload = new ByteArrayOutputStream();
            ByteArrayOutputStream log = new ByteArrayOutputStream();
            int n = Loader.run(s, in.iterator(), new UnloadSink(unload), new PrintStream(log, true, StandardCharsets.UTF_8));
            assertEquals(in.size(), n, s.program());
            assertEquals(in.size() * s.segmentLength(), unload.size());
            assertTrue(log.toString(StandardCharsets.UTF_8).contains("TOTAL INPUT RECORDS: " + n));
        }
        byte[] cust = Loaders.LOADCUST.build(data("CUSTOMER").get(0), Loaders.LOADCUST.initialState());
        assertEquals("Antonelli", Text.decode(cust, 4, 50, Ebcdic.CP1047).strip());
        assertEquals("2003-07-23 17:56:00.64 ", Text.decode(cust, 4 + 50 + 50 + 80 + 25 + 2 + 15 + 12 + 1 + 16 + 1, 23, Ebcdic.CP1047));
    }

    @Test
    void badStatusStopsAfterCountingTheFailedRecord() throws Exception {
        List<String> in = new ArrayList<>(List.of("1,\"c\",1.00,1", "2,\"c\",1.00,1", "3,\"c\",1.00,1"));
        int[] calls = {0};
        SegmentSink failSecond = (spec, seg) -> ++calls[0] == 2 ? "LB" : "  ";
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        assertEquals(2, Loader.run(Loaders.LOADACCT, in.iterator(), failSecond, new PrintStream(log, true, StandardCharsets.UTF_8)));
        assertTrue(log.toString(StandardCharsets.UTF_8).contains("BAD STATUS CODE: LB"));
    }
}
