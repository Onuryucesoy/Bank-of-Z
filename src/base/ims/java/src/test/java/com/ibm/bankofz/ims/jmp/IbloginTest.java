package com.ibm.bankofz.ims.jmp;

import com.ibm.bankofz.bridge.runtime.Ebcdic;
import com.ibm.bankofz.bridge.runtime.Text;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IbloginTest {
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-19T17:34:05.127Z"), ZoneOffset.UTC);

    static byte[] request(String custid, String pw) {
        byte[] m = IbacsumTest.request(custid);
        byte[] full = java.util.Arrays.copyOf(m, 37);
        full[1] = 37;
        Text.encode(pw, full, 21, 16, Ebcdic.CP1047);
        return full;
    }

    static FakeDli run(byte[] seg, byte[] msg, String forcedRepl) throws Exception {
        FakeDli dli = new FakeDli().message(msg);
        if (seg != null) {
            dli.segment("CUSTOMER", seg);
        }
        if (forcedRepl != null) {
            dli.forcedReplace.put("CUSTOMER", forcedRepl);
        }
        new Iblogin(dli, CustomerProgramsTest.NULL, CLOCK).run();
        return dli;
    }

    static byte[] seg(String status) {
        byte[] s = CustomerProgramsTest.customer(5, "L", "F", status);
        Text.encode("pw1", s, Customer.PASSWORD, 16, Ebcdic.CP1047); // pragma: allowlist secret
        return s;
    }

    static String msg(FakeDli d) {
        return CustomerProgramsTest.text(d.output.get(0), 4, 32).strip();
    }

    @Test
    void successStampsLastLoginAndStatus() throws Exception {
        FakeDli d = run(seg("0"), request("000000005", "pw1"), null);
        assertEquals("LOGIN SUCCESSFUL", msg(d));
        assertEquals("00240000", IbacsumTest.HEX.formatHex(d.output.get(0), 0, 4));
        byte[] after = d.db.get("CUSTOMER").get(0);
        assertEquals("1", CustomerProgramsTest.text(after, Customer.STATUS, 1));
        assertEquals("2026_10_19 17:34:05:120", CustomerProgramsTest.text(after, Customer.LASTLOGIN, 23));
    }

    @Test
    void rejectionsAndReplFailure() throws Exception {
        assertEquals("PASSWORD INVALID", msg(run(seg("0"), request("000000005", "nope"), null)));
        assertEquals("CUSTOMER ALREADY LOGGED IN", msg(run(seg("1"), request("000000005", "pw1"), null)));
        assertEquals("CUSTOMER DOES NOT EXIST", msg(run(null, request("000000005", "pw1"), null)));
        assertEquals("BAD STATUS CODE WAS RECEIVED: AM", msg(run(seg("0"), request("000000005", "pw1"), "AM")));
    }
}
