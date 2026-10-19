package com.ibm.bankofz.ims.jmp;

import com.ibm.bankofz.bridge.runtime.Binary;
import com.ibm.bankofz.bridge.runtime.Ebcdic;
import com.ibm.bankofz.bridge.runtime.Text;

import java.io.PrintStream;
import java.math.BigDecimal;
import java.util.Arrays;

/** IBGCUDAT (get customer data), paragraph for paragraph with {@code IBGCUDAT.cbl}; PSB IBGCUDAT unchanged. */
public final class Ibgcudat {
    static final int OUTPUT = 279;
    private final Dli dli;
    private final PrintStream sysout;
    private final byte[] input = new byte[21];

    public Ibgcudat(Dli dli, PrintStream sysout) {
        this.dli = dli;
        this.sysout = sysout;
    }

    public static void main(String[] args) throws Exception {
        new Ibgcudat(new UdbDli("IBGCUDAT"), System.out).run();
    }

    public void run() throws Exception {
        Mpp.loop(dli, sysout, input, () -> Mpp.llzz(getCustomerData(), OUTPUT));
    }

    byte[] getCustomerData() throws Exception {
        byte[] out = new byte[OUTPUT];
        Arrays.fill(out, (byte) 0xF0);
        byte[] seg = new byte[Customer.LENGTH];
        String dbstat = dli.call("GU", "CUSTOMER", seg, Customer.ssa(input));
        if (dbstat.equals("  ")) {
            System.arraycopy(seg, Customer.FIRSTNAME, out, 36, 50);
            System.arraycopy(seg, Customer.LASTNAME, out, 86, 50);
            System.arraycopy(seg, Customer.ADDRESS, out, 136, 80);
            System.arraycopy(seg, Customer.CITY, out, 216, 25);
            System.arraycopy(seg, Customer.STATE, out, 241, 2);
            System.arraycopy(seg, Customer.ZIPCODE, out, 243, 15);
            System.arraycopy(seg, Customer.PHONE, out, 258, 12);
            // MOVE S9(9) COMP-5 TO X(9): the unsigned digits, sign dropped.
            BigDecimal id = Binary.decode(seg, Customer.CUSTID, 9, 0, true).abs()
                    .remainder(BigDecimal.TEN.pow(9));
            Text.encode(String.format("%09d", id.longValueExact()), out, 270, 9, Ebcdic.CP1047);
        } else {
            Text.encode(Mpp.notFound(dbstat) ? "CUSTOMER DOES NOT EXIST" : Ibacsum.BAD_STATUS + dbstat,
                    out, 4, 32, Ebcdic.CP1047);
        }
        return out;
    }
}
