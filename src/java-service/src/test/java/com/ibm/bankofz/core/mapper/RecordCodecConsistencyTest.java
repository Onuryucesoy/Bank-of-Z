package com.ibm.bankofz.core.mapper;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ibm.bankofz.core.RepoPaths;
import com.ibm.bankofz.core.copybook.CopybookParser;
import com.ibm.bankofz.core.copybook.RecordCodec;
import com.ibm.bankofz.core.golden.GoldenFixture;
import com.ibm.bankofz.core.model.Account;
import com.ibm.bankofz.core.model.CobolDate;
import com.ibm.bankofz.core.model.Customer;
import com.ibm.bankofz.core.model.ProcTran;
import com.ibm.bankofz.core.model.ProcTranType;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The hand-written model codecs must produce exactly the bytes that the
 * copybook-driven {@link RecordCodec} produces from the frozen copybooks
 * and golden fixtures.
 */
class RecordCodecConsistencyTest {

    private static RecordCodec layout(String cpy) throws IOException {
        return new RecordCodec(CopybookParser.parse(RepoPaths.resolve("src/base/cics/copy/" + cpy)).primary());
    }

    @Test
    void customerCodecMatchesCustomerCopybookAndCrdtagyFixture() throws IOException {
        GoldenFixture fx = GoldenFixture.read(RepoPaths.resolve("tests/golden/fixtures/CRDTAGY1.json"));
        Map<String, String> fields = fx.cases().get(0).response();
        RecordCodec cpy = layout("CUSTOMER.cpy");
        byte[] expected = cpy.encode(fields);

        CustomerRecordCodec codec = new CustomerRecordCodec();
        Customer c = codec.decode(expected);
        assertEquals(1001L, c.customerNumber());
        assertEquals("Andrew", c.firstName());
        assertEquals(new CobolDate(15, 6, 1980), c.dateOfBirth());
        assertEquals(123, c.creditScore());
        assertEquals(CobolDate.ZERO, c.creditScoreReviewDate());
        assertArrayEquals(expected, codec.encode(c));
        assertEquals(CustomerRecordCodec.LENGTH, cpy.length());
    }

    @Test
    void accountCodecMatchesAccountCopybook() throws IOException {
        Account a = new Account("ACCT", 1, "987654", 10, "CURRENT", new BigDecimal("0.50"),
                new CobolDate(1, 2, 2020), 500, new CobolDate(1, 9, 2026), new CobolDate(1, 10, 2026),
                new BigDecimal("-75.20"), new BigDecimal("1234.56"));
        Map<String, String> f = new LinkedHashMap<>();
        f.put("ACCOUNT-EYE-CATCHER", "ACCT");
        f.put("ACCOUNT-CUST-NO", "1");
        f.put("ACCOUNT-SORT-CODE", "987654");
        f.put("ACCOUNT-NUMBER", "10");
        f.put("ACCOUNT-TYPE", "CURRENT");
        f.put("ACCOUNT-INTEREST-RATE", "0.50");
        f.put("ACCOUNT-OPENED", "01022020");
        f.put("ACCOUNT-OVERDRAFT-LIMIT", "500");
        f.put("ACCOUNT-LAST-STMT-DATE", "01092026");
        f.put("ACCOUNT-NEXT-STMT-DATE", "01102026");
        f.put("ACCOUNT-AVAILABLE-BALANCE", "-75.20");
        f.put("ACCOUNT-ACTUAL-BALANCE", "1234.56");
        RecordCodec cpy = layout("ACCOUNT.cpy");
        AccountRecordCodec codec = new AccountRecordCodec();
        byte[] bytes = codec.encode(a);
        assertArrayEquals(cpy.encode(f), bytes);
        assertEquals(a, codec.decode(bytes));
        assertEquals("1", cpy.decode(bytes).get("ACCOUNT-OPENED-DAY")); // DDMMYYYY redefinition
    }

    @Test
    void procTranCodecMatchesProcTranCopybook() throws IOException {
        ProcTran p = new ProcTran("PRTR", "987654", 0, new CobolDate(18, 10, 2026), 111015, 4711,
                ProcTranType.BRANCH_CREATE_CUSTOMER.code(), "9876540000001001Andrew Smith  1980-06-15",
                BigDecimal.ZERO.setScale(2));
        RecordCodec cpy = layout("PROCTRAN.cpy");
        byte[] bytes = new ProcTranRecordCodec().encode(p);
        Map<String, String> decoded = cpy.decode(bytes);
        assertEquals("2026", decoded.get("PROC-TRAN-DATE-GRP-YYYY")); // YYYYMMDD, unlike ACCOUNT
        assertEquals("1001", decoded.get("PROC-DESC-CRECUS-CUSTOMER"));
        assertEquals(ProcTranType.BRANCH_CREATE_CUSTOMER, new ProcTranRecordCodec().decode(bytes).typeCode());
        assertEquals(p, new ProcTranRecordCodec().decode(bytes));
    }

    @Test
    void oversizeTextIsRejectedRatherThanTruncated() {
        Customer c = new Customer("CUST", "987654", 1, "Mr", "x".repeat(51), "", CobolDate.ZERO, "", "", "", "",
                "", "", "", CobolDate.ZERO, 0, CobolDate.ZERO);
        assertThrows(RuntimeException.class, () -> new CustomerRecordCodec().encode(c));
    }

    @Test
    void rowMappersPreserveDb2Representations() {
        Customer c = new Customer("CUST", "987654", 42, "Ms", "Jo", "Bloggs", new CobolDate(2, 3, 1990), "1", "a",
                "b", "c", "d", "e", "ACTIVE", new CobolDate(4, 5, 2020), 700, new CobolDate(6, 7, 2020));
        var row = CustomerRowMapper.toRow(c);
        assertEquals("0000000042", row.customerNumber());
        assertEquals(19900302, row.dateOfBirth());
        assertEquals((short) 700, row.creditScore());
        assertEquals(c, CustomerRowMapper.fromRow(row));

        Account zeroDates = new Account("ACCT", 1, "987654", 2, "ISA", new BigDecimal("1.00"), CobolDate.ZERO, 0,
                CobolDate.ZERO, CobolDate.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        assertEquals(null, AccountRowMapper.toRow(zeroDates).opened());
        assertEquals(zeroDates, AccountRowMapper.fromRow(AccountRowMapper.toRow(zeroDates)));
    }
}
