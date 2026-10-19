package com.ibm.bankofz.ims.jmp;

import com.ibm.bankofz.bridge.runtime.Binary;
import com.ibm.bankofz.bridge.runtime.Ebcdic;
import com.ibm.bankofz.bridge.runtime.PackedDecimal;
import com.ibm.bankofz.bridge.runtime.Text;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IbacsumTest {
    static final Path REPO = Path.of(System.getProperty("bankz.repoRoot"));
    static final HexFormat HEX = HexFormat.of().withUpperCase();

    static byte[] account(long accid, String type, String balance) {
        byte[] s = new byte[33];
        Binary.encode(BigDecimal.valueOf(accid), s, 0, 18, 0, true, true);
        Text.encode(type, s, 8, 1, Ebcdic.CP1047);
        PackedDecimal.encode(new BigDecimal(balance), s, 9, 15, 2, true);
        return s;
    }

    static byte[] custaccs(long custid, long accid) {
        byte[] s = new byte[16];
        Binary.encode(BigDecimal.valueOf(custid), s, 0, 9, 0, true, true);
        Binary.encode(BigDecimal.valueOf(accid), s, 4, 18, 0, true, true);
        Binary.encode(BigDecimal.ONE, s, 12, 9, 0, true, true);
        return s;
    }

    static byte[] request(String custid) {
        byte[] m = new byte[12 + custid.length()];
        Binary.encode(BigDecimal.valueOf(m.length), m, 0, 4, 0, false, false);
        Text.encode("IBACSUM " + custid, m, 4, 8 + custid.length(), Ebcdic.CP1047);
        return m;
    }

    static String run(FakeDli dli) throws Exception {
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        new Ibacsum(dli, new PrintStream(log, true, StandardCharsets.UTF_8)).run();
        return log.toString(StandardCharsets.UTF_8);
    }

    static String msg(String s) {
        byte[] b = new byte[32];
        Text.encode(s, b, 0, 32, Ebcdic.CP1047);
        return HEX.formatHex(b);
    }

    @Test
    void twoAccountsGiveTheExactCobolOutputArea() throws Exception {
        FakeDli dli = new FakeDli()
                .segment("CUSTACCS", custaccs(1, 101)).segment("CUSTACCS", custaccs(1, 202))
                .segment("CUSTACCS", custaccs(2, 303))
                .segment("ACCOUNT", account(101, "C", "8830.00")).segment("ACCOUNT", account(202, "S", "-12.34"))
                .message(request("000000001"));
        run(dli);
        assertEquals(1, dli.output.size());
        assertEquals("0047" + "0000" + "F0".repeat(32) + "F2"
                + "000000000883000C" + "C3" + "0000000000000065"
                + "000000000001234D" + "E2" + "00000000000000CA", HEX.formatHex(dli.output.get(0)));
        assertEquals(1, dli.syncpoints);
    }

    @Test
    void notFoundAndBadStatusMessages() throws Exception {
        FakeDli none = new FakeDli().message(request("000000009"));
        run(none);
        assertEquals("0025" + "0000" + msg(Ibacsum.NOCUSTOMER) + "F0", HEX.formatHex(none.output.get(0)));

        FakeDli orphan = new FakeDli().segment("CUSTACCS", custaccs(1, 101)).message(request("000000001"));
        run(orphan);
        assertEquals(msg(Ibacsum.NOACCOUNT), HEX.formatHex(orphan.output.get(0), 4, 36));

        FakeDli bad = new FakeDli().segment("CUSTACCS", custaccs(1, 101)).message(request("000000001"));
        bad.forced.put("ACCOUNT", "AI");
        run(bad);
        assertEquals(msg("BAD STATUS CODE WAS RECEIVED: AI"), HEX.formatHex(bad.output.get(0), 4, 36));
    }

    @Test
    void shorterMessageKeepsTheTailOfThePreviousInputArea() throws Exception {
        FakeDli dli = new FakeDli().message(request("000000001")).message(request("2"));
        run(dli);
        assertEquals("0BEBC201", HEX.formatHex(dli.ssas.get(dli.ssas.size() - 1).value()));
    }

    @Test
    void ioPcbErrorsAreDisplayedAndTheLoopContinues() throws Exception {
        FakeDli dli = new FakeDli().message("AB").message(request("000000001"));
        String log = run(dli);
        assertTrue(log.contains("GU FROM IOPCB FAILED WITH STATUS CODE: AB"), log);
        assertEquals(1, dli.output.size());
    }

    @Test
    void aSeventhAccountStopsInsteadOfOverwritingStorage() {
        FakeDli dli = new FakeDli().message(request("000000001"));
        for (int i = 1; i <= 7; i++) {
            dli.segment("CUSTACCS", custaccs(1, i)).segment("ACCOUNT", account(i, "C", "1.00"));
        }
        assertThrows(IllegalStateException.class, () -> run(dli));
    }

    @Test
    void layoutMatchesTheZosConnectResponseContractAndTheDbds() throws Exception {
        String dai = Files.readString(REPO.resolve("src/api/src/main/zosAssets/IBACSUM/providerFiles/response.dai"));
        String[][] expected = {{"OUTPUT-AREA", "1", "139"}, {"MSG-OUT", "5", "32"}, {"TOTAL-ACCS", "37", "1"},
            {"ACCOUNT-SUMMARY", "38", String.valueOf(Ibacsum.SUMMARY * Ibacsum.MAX_ACCOUNTS)}};
        for (String[] e : expected) {
            Matcher m = Pattern.compile("name=\"" + e[0] + "\"[^>]*>\\s*<startPos>(\\d+)</startPos>\\s*<bytes>(\\d+)")
                    .matcher(dai);
            assertTrue(m.find(), e[0]);
            assertEquals(e[1] + "/" + e[2], m.group(1) + "/" + m.group(2), e[0]);
        }
        int summary = 0;
        for (String[] e : new String[][] {{"BALANCE-AS", "8"}, {"ACCTYPE-AS", "1"}, {"ACCID-AS", "8"}}) {
            Matcher m = Pattern.compile("name=\"" + e[0] + "\"[^>]*>\\s*<bytes>(\\d+)").matcher(dai);
            assertTrue(m.find(), e[0]);
            assertEquals(e[1], m.group(1), e[0]);
            summary += Integer.parseInt(e[1]);
        }
        assertEquals(Ibacsum.SUMMARY, summary);
        assertEquals(139, Ibacsum.OUTPUT_MAX);
        for (String[] k : new String[][] {{"ACCOUNT", "ACCID"}, {"CUSTACCS", "CUSTID"}}) {
            String dbd = Files.readString(REPO.resolve("src/base/ims/DBD/" + k[0] + ".asm"));
            Matcher m = Pattern.compile("(?s)NAME=\\(" + k[1] + ",SEQ[^)]*\\),.{0,300}?START=(\\d+)").matcher(dbd);
            assertTrue(m.find(), k[0]);
            assertEquals("1", m.group(1), k[0] + "." + k[1]);
        }
    }
}
