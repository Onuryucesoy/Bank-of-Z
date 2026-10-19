package com.ibm.bankofz.ims.jmp;

import com.ibm.bankofz.bridge.runtime.Binary;
import com.ibm.bankofz.bridge.runtime.Ebcdic;
import com.ibm.bankofz.bridge.runtime.Numval;
import com.ibm.bankofz.bridge.runtime.Text;

import java.math.BigDecimal;

/** CUSTOMER segment offsets (the CUSTOMER-SEG / CUST-SEG layouts in the IMS COBOL; SEGM BYTES=279). */
final class Customer {
    static final int LENGTH = 279;
    static final int CUSTID = 0;
    static final int LASTNAME = 4;
    static final int FIRSTNAME = 54;
    static final int ADDRESS = 104;
    static final int CITY = 184;
    static final int STATE = 209;
    static final int ZIPCODE = 211;
    static final int PHONE = 226;
    static final int STATUS = 238;
    static final int PASSWORD = 239;
    static final int CUSTTYPE = 255;
    static final int LASTLOGIN = 256;

    private Customer() {
    }

    /** {@code COMPUTE CUSTID = FUNCTION NUMVAL(IN-CUSTID)} into the S9(9) COMP-5 SSA value. */
    static Ssa ssa(byte[] input) {
        BigDecimal custid = Numval.of(Text.decode(input, 12, 9, Ebcdic.CP1047));
        byte[] key = new byte[4];
        Binary.encode(custid, key, 0, 9, 0, true, true);
        return new Ssa("CUSTOMER", "CUSTID", CUSTID, key);
    }
}
