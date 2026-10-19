package com.ibm.bankofz.bridge.copybook;

import com.ibm.bankofz.bridge.copybook.cics.CicsCopybooks;
import com.ibm.bankofz.bridge.copybook.cics.Customer;
import com.ibm.bankofz.bridge.copybook.cics.Inqacccu;
import com.ibm.bankofz.bridge.copybook.cics.Inqtranl;
import com.ibm.bankofz.bridge.copybook.cics.Paydbcr;
import com.ibm.bankofz.bridge.copybook.cics.Procisrt;
import com.ibm.bankofz.bridge.copybook.cics.Proctran;
import com.ibm.bankofz.bridge.copybook.cics.Sortcode;
import com.ibm.bankofz.bridge.copybook.ims.IbtranOutputArea;
import com.ibm.bankofz.bridge.copybook.ims.ImsCopybooks;
import com.ibm.bankofz.bridge.runtime.FieldSpec;
import com.ibm.bankofz.bridge.runtime.Hex;
import com.ibm.bankofz.bridge.runtime.RecordLayout;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CopybookLayoutTest {

    /** Lengths cross-checked against the COBOL compiler listings' data maps / the z/OS Connect .si files. */
    @Test
    void recordLengths() {
        Map<String, Integer> expected = Map.ofEntries(
                Map.entry("CUSTOMER", 397), Map.entry("ACCOUNT", 98), Map.entry("PROCTRAN", 99),
                Map.entry("CRECUST", 399), Map.entry("INQCUSTZ", 403), Map.entry("PAYDBCR", 132),
                Map.entry("XFRFUN", 90), Map.entry("CREACC", 100), Map.entry("INQACC", 103),
                Map.entry("INQACCCU", 1981), Map.entry("INQACCS", 1970), Map.entry("PROCISRT", 131),
                Map.entry("ABNDINFO", 681), Map.entry("INQTRANL", 14551));
        expected.forEach((member, len) -> assertEquals(len, CicsCopybooks.LAYOUTS.get(member).length(), member));
        assertEquals(150, ImsCopybooks.LAYOUTS.get("IBTRAN/OUTPUT-AREA").length());
        assertEquals(56, ImsCopybooks.LAYOUTS.get("IBTRAN/INPUT-AREA").length());
    }

    @Test
    void everyCopybookIsAccountedFor() {
        assertEquals(java.util.Set.of("RESPSTR", "WAZI"), CicsCopybooks.NOT_DATA.keySet());
        assertEquals(4, CicsCopybooks.TABLES.size());
        assertEquals(17, CicsCopybooks.TABLES.get("CUSTDB2").columns().size());
        assertTrue(ImsCopybooks.NOT_DATA.isEmpty());
    }

    @Test
    void layoutsAreContiguousAndCanonicalLeavesCoverEveryByte() {
        for (Map<String, RecordLayout> tree : java.util.List.of(CicsCopybooks.LAYOUTS, ImsCopybooks.LAYOUTS)) {
            for (RecordLayout l : tree.values()) {
                boolean[] covered = new boolean[l.length()];
                for (FieldSpec f : l.canonicalLeaves()) {
                    int[] idx = new int[f.dimensions()];
                    cover(f, idx, 0, covered, l.name());
                }
                for (int i = 0; i < covered.length; i++) {
                    assertTrue(covered[i], l.name() + " byte " + i + " not covered");
                }
            }
        }
    }

    private static void cover(FieldSpec f, int[] idx, int dim, boolean[] covered, String rec) {
        if (dim == idx.length) {
            int at = f.offsetOf(idx);
            for (int i = at; i < at + f.length(); i++) {
                assertFalse(covered[i], rec + " byte " + i + " covered twice by " + f.path());
                covered[i] = true;
            }
            return;
        }
        for (int i = 0; i < f.maxOccurs(dim); i++) {
            idx[dim] = i;
            cover(f, idx, dim + 1, covered, rec);
        }
    }

    @Test
    void redefinesShareStorage() {
        assertEquals(Procisrt.PROCISRT_DEBIT_STRUCT.offset(), Procisrt.PROCISRT_CREACC_STRUCT.offset());
        assertEquals(Procisrt.PROCISRT_DEBIT_AMOUNT.offset(), Procisrt.PROCISRT_XFR_L_AMOUNT.offset());
        assertTrue(Procisrt.PROCISRT_CREACC_STRUCT.underRedefines());
        assertTrue(Procisrt.PROCISRT_CREACC_TYPE.underRedefines());
        assertFalse(Procisrt.PROCISRT_DEBIT_AMOUNT.underRedefines());

        Procisrt p = new Procisrt();
        p.setProcisrtXfrLAmount(new BigDecimal("-12.34"));
        assertEquals(new BigDecimal("-12.34"), p.getProcisrtDebitAmount());
        assertEquals(new BigDecimal("-12.34"), p.getProcisrtCreditAmount());
    }

    @Test
    void occursDependingOn() {
        Inqacccu r = new Inqacccu();
        r.initialize();
        r.setNumberOfAccounts(3);
        assertEquals(Inqacccu.ODO_TABLE_OFFSET + 3 * 98, r.actualLength());
        r.setCommAccno(1234, 2);
        assertEquals(1234, r.getCommAccno(2));
        int at = Inqacccu.COMM_ACCNO.offsetOf(2);
        assertEquals(Inqacccu.ODO_TABLE_OFFSET + 2 * 98 + (Inqacccu.COMM_ACCNO.offset() - Inqacccu.ODO_TABLE_OFFSET), at);
        // A COBOL caller LINKing with only 3 occurrences is accepted: the buffer only needs the fixed part.
        new Inqacccu(new byte[Inqacccu.ODO_TABLE_OFFSET]);
    }

    @Test
    void imsAccountSummaryStrideMatchesAccountSegment() {
        // BALANCE-AS S9(13)V9(2) COMP-3 (8) + ACCTYPE-AS X(1) + ACCID-AS S9(18) COMP-5 (8)
        assertEquals(17, IbtranOutputArea.ACCOUNT_SUMMARY.length());
        IbtranOutputArea o = new IbtranOutputArea();
        o.setBalanceAs(new BigDecimal("-1500.25"), 5);
        o.setAccidAs(9_000_000_000L, 5);
        int at = IbtranOutputArea.BALANCE_AS.offsetOf(5);
        assertEquals("000000000150025D", Hex.of(o.buffer(), at, 8));
        assertEquals("0000000218711A00", Hex.of(o.buffer(), at + 9, 8));
    }

    @Test
    void fixedOccursTable() {
        assertEquals(100, Inqtranl.INQTRANL_TRANSACTIONS.maxOccurs(0));
        Inqtranl t = new Inqtranl();
        t.initialize();
        assertEquals(Inqtranl.LENGTH, t.toBytes().length);
    }

    @Test
    void level88AndInitialize() {
        Proctran p = new Proctran();
        p.initialize();
        p.setProcTranValid();
        assertTrue(p.isProcTranValid());
        p.setProcTyDebit();
        assertTrue(p.isProcTyDebit());
        assertFalse(p.isProcTyCredit());
        Customer c = new Customer();
        c.initialize();
        assertFalse(c.isCustomerEyecatcherValue());
        c.setCustomerEyecatcherValue();
        assertEquals("CUST", c.getCustomerEyecatcher());
        Sortcode s = new Sortcode();
        s.applyValues();
        assertEquals(987654, s.getSortcode());
    }

    @Test
    void paydbcrGoldenBytes() {
        Paydbcr p = new Paydbcr();
        p.initialize();
        p.setCommAccno("00000042");
        p.setCommAmt(new BigDecimal("-10.5"));
        p.setCommSortc(987654);
        String hex = Hex.of(p.toBytes());
        assertEquals("F0F0F0F0F0F0F4F2" + "F0F0F0F0F0F0F0F0F1F0F5D0" + "F9F8F7F6F5F4", hex.substring(0, 52));
        assertEquals(new BigDecimal("-10.50"), p.getCommAmt());
    }
}
