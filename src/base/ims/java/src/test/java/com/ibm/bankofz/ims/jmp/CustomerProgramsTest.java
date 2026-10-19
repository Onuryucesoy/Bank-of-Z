package com.ibm.bankofz.ims.jmp;

import com.ibm.bankofz.bridge.runtime.Binary;
import com.ibm.bankofz.bridge.runtime.Ebcdic;
import com.ibm.bankofz.bridge.runtime.Text;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomerProgramsTest {
    static final PrintStream NULL = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);

    static byte[] customer(long id, String last, String first, String status) {
        byte[] s = new byte[Customer.LENGTH];
        Text.encode("", s, 4, Customer.LENGTH - 4, Ebcdic.CP1047);
        Binary.encode(BigDecimal.valueOf(id), s, 0, 9, 0, true, true);
        Text.encode(last, s, Customer.LASTNAME, 50, Ebcdic.CP1047);
        Text.encode(first, s, Customer.FIRSTNAME, 50, Ebcdic.CP1047);
        Text.encode("1 Main St", s, Customer.ADDRESS, 80, Ebcdic.CP1047);
        Text.encode("Austin", s, Customer.CITY, 25, Ebcdic.CP1047);
        Text.encode("TX", s, Customer.STATE, 2, Ebcdic.CP1047);
        Text.encode("78701", s, Customer.ZIPCODE, 15, Ebcdic.CP1047);
        Text.encode("512-555-0100", s, Customer.PHONE, 12, Ebcdic.CP1047);
        Text.encode(status, s, Customer.STATUS, 1, Ebcdic.CP1047);
        return s;
    }

    static String text(byte[] b, int off, int len) {
        return Text.decode(b, off, len, Ebcdic.CP1047);
    }

    @Test
    void gcudatMapsTheSegmentIntoTheOutputAreaOrder() throws Exception {
        FakeDli dli = new FakeDli().segment("CUSTOMER", customer(42, "Antonelli", "Ana", "1"))
                .message(IbacsumTest.request("000000042"));
        new Ibgcudat(dli, NULL).run();
        byte[] out = dli.output.get(0);
        assertEquals(279, out.length);
        assertEquals("01170000", IbacsumTest.HEX.formatHex(out, 0, 4));
        assertEquals("0".repeat(32), text(out, 4, 32));
        assertEquals("Ana", text(out, 36, 50).strip());
        assertEquals("Antonelli", text(out, 86, 50).strip());
        assertEquals("512-555-0100", text(out, 258, 12));
        assertEquals("000000042", text(out, 270, 9));
    }

    @Test
    void gcudatNotFoundLeavesZerosInEveryOtherField() throws Exception {
        FakeDli dli = new FakeDli().message(IbacsumTest.request("000000042"));
        new Ibgcudat(dli, NULL).run();
        byte[] out = dli.output.get(0);
        assertEquals("CUSTOMER DOES NOT EXIST", text(out, 4, 32).strip());
        assertEquals("0".repeat(243), text(out, 36, 243));
    }

    @Test
    void logoutReplacesStatusAndAnswersSuccess() throws Exception {
        FakeDli dli = new FakeDli().segment("CUSTOMER", customer(7, "L", "F", "1"))
                .message(IbacsumTest.request("000000007"));
        new Iblogout(dli, NULL).run();
        assertEquals("0", text(dli.db.get("CUSTOMER").get(0), Customer.STATUS, 1));
        byte[] expected = customer(7, "L", "F", "0");
        assertArrayEquals(expected, dli.db.get("CUSTOMER").get(0));
        assertEquals("0024" + "0000", IbacsumTest.HEX.formatHex(dli.output.get(0), 0, 4));
        assertEquals("LOGOFF SUCCESSFUL", text(dli.output.get(0), 4, 32).strip());
    }

    @Test
    void logoutStillSaysSuccessWhenReplFailsAsTheCobolDoes() throws Exception {
        FakeDli dli = new FakeDli().segment("CUSTOMER", customer(7, "L", "F", "1"))
                .message(IbacsumTest.request("000000007"));
        dli.forcedReplace.put("CUSTOMER", "AM");
        new Iblogout(dli, NULL).run();
        assertEquals("LOGOFF SUCCESSFUL", text(dli.output.get(0), 4, 32).strip());
        assertEquals("1", text(dli.db.get("CUSTOMER").get(0), Customer.STATUS, 1));
    }

    @Test
    void logoutUnknownCustomer() throws Exception {
        FakeDli dli = new FakeDli().message(IbacsumTest.request("000000007"));
        new Iblogout(dli, NULL).run();
        assertEquals("FAILED UPDATE FOR LOGOFF", text(dli.output.get(0), 4, 32).strip());
    }

    @Test
    void psbsNameTheCustomerPcbAndLayoutsMatchTheDbd() throws Exception {
        Path repo = IbacsumTest.REPO;
        for (String psb : new String[] {"IBGCUDAT", "IBLOGOUT"}) {
            assertTrue(Files.readString(repo.resolve("src/base/ims/PSB/" + psb + ".asm")).contains("PCBNAME=CUSTOMER"), psb);
        }
        String dbd = Files.readString(repo.resolve("src/base/ims/DBD/CUSTOMER.asm"));
        assertTrue(dbd.contains("BYTES=(" + Customer.LENGTH + ")"), "CUSTOMER SEGM BYTES");
    }
}
