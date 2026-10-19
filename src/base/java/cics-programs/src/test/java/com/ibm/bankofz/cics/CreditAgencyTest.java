package com.ibm.bankofz.cics;

import com.ibm.bankofz.bridge.copybook.cics.Customer;
import com.ibm.bankofz.bridge.runtime.Ebcdic;
import com.ibm.bankofz.bridge.runtime.Text;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreditAgencyTest {
    static final PrintStream NULL = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);

    static final class FakeCics implements CreditAgency.Cics {
        final Map<String, byte[]> containers = new HashMap<>();
        final List<Integer> delays = new ArrayList<>();
        int task = 4711;
        boolean failDelay;
        String abend;

        @Override public int taskNumber() { return task; }
        @Override public void delay(int s) throws Exception { if (failDelay) { throw new Exception("x"); } delays.add(s); }
        @Override public byte[] getContainer(String n) { byte[] b = containers.get(n); return b == null ? null : b.clone(); }
        @Override public boolean putContainer(String n, byte[] d) { containers.put(n, d.clone()); return true; }
        @Override public void abend(String c) { abend = c; }
    }

    static byte[] customer() {
        byte[] c = new byte[Customer.LENGTH];
        Text.encode("CUST", c, 0, 4, Ebcdic.CP037);
        Text.encode("x".repeat(Customer.LENGTH - 4), c, 4, Customer.LENGTH - 4, Ebcdic.CP037);
        return c;
    }

    @Test
    void onlyTheCreditScoreChangesAndStaysInRange() throws Exception {
        for (int task = 1; task < 300; task++) {
            FakeCics cics = new FakeCics();
            cics.task = task;
            cics.containers.put("CIPC", customer());
            CreditAgency.run(cics, "CIPC", NULL);
            byte[] out = cics.containers.get("CIPC");
            int off = Customer.CUSTOMER_CREDIT_SCORE.offset();
            int score = Integer.parseInt(Text.decode(out, off, 3, Ebcdic.CP037));
            assertTrue(score >= 1 && score <= 998, "score " + score);
            byte[] expected = customer();
            System.arraycopy(out, off, expected, off, 3);
            assertArrayEquals(expected, out);
            assertTrue(cics.delays.get(0) == 1 || cics.delays.get(0) == 2);
        }
    }

    @Test
    void missingOrOversizedContainerIsNotPutBack() throws Exception {
        FakeCics none = new FakeCics();
        CreditAgency.run(none, "CIPA", NULL);
        assertNull(none.containers.get("CIPA"));
        FakeCics big = new FakeCics();
        big.containers.put("CIPA", new byte[Customer.LENGTH + 1]);
        CreditAgency.run(big, "CIPA", NULL);
        assertEquals(Customer.LENGTH + 1, big.containers.get("CIPA").length);
    }

    @Test
    void delayFailureAbendsPlop() throws Exception {
        FakeCics cics = new FakeCics();
        cics.failDelay = true;
        cics.containers.put("CIPA", customer());
        CreditAgency.run(cics, "CIPA", NULL);
        assertEquals("PLOP", cics.abend);
        assertArrayEquals(customer(), cics.containers.get("CIPA"));
    }

    @Test
    void containerNamesMatchTheCobol() throws Exception {
        Path cobol = Path.of(System.getProperty("user.dir")).resolve("../../cics/cobol").normalize();
        String letters = "ABCDE";
        for (int i = 1; i <= 5; i++) {
            String src = Files.readString(cobol.resolve("CRDTAGY" + i + ".cbl"));
            assertTrue(src.contains("MOVE 'CIP" + letters.charAt(i - 1)), "CRDTAGY" + i);
            assertTrue(src.contains("COPY CUSTOMER."), "CRDTAGY" + i);
        }
        assertEquals(386, Customer.CUSTOMER_CREDIT_SCORE.offset());
    }
}
