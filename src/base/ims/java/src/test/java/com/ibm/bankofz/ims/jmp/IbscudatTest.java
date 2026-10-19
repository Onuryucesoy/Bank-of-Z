package com.ibm.bankofz.ims.jmp;

import com.ibm.bankofz.bridge.runtime.Binary;
import com.ibm.bankofz.bridge.runtime.Ebcdic;
import com.ibm.bankofz.bridge.runtime.Text;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class IbscudatTest {
    static byte[] request(byte[] customer) {
        byte[] m = new byte[Ibscudat.INPUT];
        Binary.encode(BigDecimal.valueOf(m.length), m, 0, 4, 0, false, false);
        Text.encode("IBSCUDAT", m, 4, 8, Ebcdic.CP1047);
        System.arraycopy(customer, 0, m, 12, Ibscudat.CUSTOMER_IN);
        return m;
    }

    @Test
    void replacesTheEditableFieldsAndKeepsStatusPasswordAndLogin() throws Exception {
        byte[] stored = CustomerProgramsTest.customer(7, "Old", "Name", "1");
        Text.encode("secret-ish", stored, Customer.PASSWORD, 16, Ebcdic.CP1047); // pragma: allowlist secret
        byte[] update = CustomerProgramsTest.customer(7, "New", "Person", "9");
        FakeDli dli = new FakeDli().segment("CUSTOMER", stored.clone()).message(request(update));
        new Ibscudat(dli, CustomerProgramsTest.NULL).run();
        byte[] after = dli.db.get("CUSTOMER").get(0);
        byte[] expected = update.clone();
        System.arraycopy(stored, Customer.STATUS, expected, Customer.STATUS, Customer.LENGTH - Customer.STATUS);
        assertArrayEquals(expected, after);
        byte[] out = dli.output.get(0);
        assertEquals(274, out.length);
        assertEquals("0".repeat(32), CustomerProgramsTest.text(out, 4, 32));
        assertArrayEquals(Arrays.copyOf(update, Ibscudat.CUSTOMER_IN), Arrays.copyOfRange(out, 36, 274));
    }

    @Test
    void replFailureAndUnknownCustomer() throws Exception {
        byte[] c = CustomerProgramsTest.customer(7, "A", "B", "1");
        FakeDli fail = new FakeDli().segment("CUSTOMER", c).message(request(c));
        fail.forcedReplace.put("CUSTOMER", "AM");
        new Ibscudat(fail, CustomerProgramsTest.NULL).run();
        assertEquals("UNABLE TO UPDATE CUSTOMER: AM", CustomerProgramsTest.text(fail.output.get(0), 4, 32).strip());
        assertEquals("0".repeat(238), CustomerProgramsTest.text(fail.output.get(0), 36, 238));

        FakeDli none = new FakeDli().message(request(c));
        new Ibscudat(none, CustomerProgramsTest.NULL).run();
        assertEquals("CUSTOMER DOES NOT EXIST", CustomerProgramsTest.text(none.output.get(0), 4, 32).strip());
    }
}
