package com.bankofz.migration.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Re-reads the canonical copybooks and checks every {@link Pic}-annotated DTO
 * component against them: PIC length/scale/sign, USAGE, REDEFINES date groups,
 * and that the Java type is the one the PIC demands (BigDecimal for any
 * fractional or packed field, LocalDate for date groups).
 */
class CopybookConformanceTest {

    static Stream<Class<? extends Record>> dtos() {
        return Stream.of(CustomerRecord.class, Address.class, AccountRecord.class,
                NewCustomerNumberRequest.class, NewCustomerNumberResponse.class,
                NewAccountNumberRequest.class, NewAccountNumberResponse.class,
                BankTransactionRecord.class);
    }

    @ParameterizedTest
    @MethodSource("dtos")
    void everyAnnotatedComponentMatchesItsCopybookItem(Class<? extends Record> dto) {
        List<String> checked = new ArrayList<>();
        for (RecordComponent rc : dto.getRecordComponents()) {
            Pic pic = rc.getAnnotation(Pic.class);
            if (pic == null) {
                assertTrue(rc.getType().isRecord(), dto.getSimpleName() + "." + rc.getName()
                        + " has no @Pic and is not a nested DTO");
                continue;
            }
            CopybookParser.Item item = CopybookParser.read(pic.copybook()).item(pic.field());
            PicClause declared = PicClause.parse(pic.value());
            String where = dto.getSimpleName() + "." + rc.getName() + " <- " + pic.field();
            if (item.isGroup()) {
                assertFalse(declared.alphanumeric(), where);
                assertEquals(declared.length(), item.numericGroupDigits(), where + " group digits");
            } else {
                PicClause actual = PicClause.parse(item.pic());
                assertTrue(declared.sameShape(actual), where + ": @Pic " + pic.value() + " vs copybook " + item.pic());
                assertEquals(item.usage(), pic.usage(), where + " usage");
            }
            List<CopybookParser.Item> redefiners = CopybookParser.read(pic.copybook()).redefinersOf(pic.field());
            if (pic.redefinedBy().isEmpty()) {
                assertTrue(redefiners.isEmpty(), where + " is REDEFINED but @Pic does not say so");
            } else {
                assertEquals(List.of(pic.redefinedBy()), redefiners.stream().map(CopybookParser.Item::name).toList(),
                        where);
                assertDayMonthYear(redefiners.get(0), where);
            }
            assertJavaType(rc, declared, item, pic, where);
            checked.add(rc.getName());
        }
        assertFalse(checked.isEmpty());
    }

    private static void assertJavaType(RecordComponent rc, PicClause pic, CopybookParser.Item item, Pic ann,
                                       String where) {
        Class<?> type = rc.getType();
        boolean dateGroup = item.isGroup() || !ann.redefinedBy().isEmpty();
        if (dateGroup) {
            assertEquals(LocalDate.class, type, where + " is a DDMMYYYY date group");
        } else if (!pic.alphanumeric() && (pic.scale() > 0 || ann.usage() == Usage.COMP_3)) {
            assertEquals(BigDecimal.class, type, where + " is fractional/packed and must be BigDecimal");
        }
    }

    /** The REDEFINES group (or a CUSTOMER.cpy date group) is DAY 99, MONTH 99, YEAR 9999, in that order. */
    private static void assertDayMonthYear(CopybookParser.Item group, String where) {
        List<CopybookParser.Item> f = group.fields();
        assertEquals(3, f.size(), where);
        assertTrue(f.get(0).name().endsWith("-DAY"), where);
        assertTrue(f.get(1).name().endsWith("-MONTH"), where);
        assertTrue(f.get(2).name().endsWith("-YEAR"), where);
        assertEquals(List.of(2, 2, 4), f.stream().map(i -> PicClause.parse(i.pic()).length()).toList(), where);
    }

    @Test
    void customerDateGroupsAreDayMonthYear() {
        CopybookParser cpy = CopybookParser.read(Copybooks.CUSTOMER);
        for (String g : List.of("CUSTOMER-DOB", "CUSTOMER-CREATED-DATE", "CUSTOMER-CS-REVIEW-DATE")) {
            assertDayMonthYear(cpy.item(g), g);
        }
    }

    @Test
    void customerStatusEnumMatches88Levels() {
        List<String> values = CopybookParser.read(Copybooks.CUSTOMER).item("CUSTOMER-STATUS").conditions()
                .stream().map(CopybookParser.Item::value).toList();
        assertEquals(values, Stream.of(CustomerStatus.values()).map(CustomerStatus::cobolValue).toList());
        assertEquals(List.of("ACTIVE", "INACTIVE", "SUSPENDED"),
                Stream.of(CustomerStatus.values()).map(Enum::name).toList());
    }

    @Test
    void counterFunctionEnumMatches88LevelsOfBothInterfaces() {
        List<String> expected = Stream.of(CounterFunction.values()).map(f -> String.valueOf(f.code())).toList();
        assertEquals(List.of("G", "R", "C"), expected);
        for (String[] cpy : new String[][] {{Copybooks.NEWCUSNO, "NEWCUSNO"}, {Copybooks.NEWACCNO, "NEWACCNO"}}) {
            CopybookParser.Item fn = CopybookParser.read(cpy[0]).item(cpy[1] + "-FUNCTION");
            // 88-levels are written at column 8 in these copybooks, so they hang off the 03 item.
            assertEquals(expected, fn.conditions().stream().map(CopybookParser.Item::value).toList(), cpy[0]);
            assertEquals(List.of(cpy[1] + "-FUNCTION-GETNEW", cpy[1] + "-FUNCTION-ROLLBACK",
                    cpy[1] + "-FUNCTION-CURRENT"), fn.conditions().stream().map(CopybookParser.Item::name).toList());
        }
    }

    @Test
    void eyecatchersMatch88Levels() {
        assertEquals(CopybookParser.read(Copybooks.CUSTOMER).item("CUSTOMER-EYECATCHER-VALUE").value(),
                CustomerRecord.EYECATCHER);
        assertEquals(CopybookParser.read(Copybooks.ACCOUNT).item("ACCOUNT-EYECATCHER-VALUE").value(),
                AccountRecord.EYECATCHER);
    }

    @Test
    void imsHistoryCopybooksAreIdenticalSoOneDtoCoversBoth() {
        CopybookParser g = CopybookParser.read(Copybooks.IBGHIST);
        CopybookParser s = CopybookParser.read("src/base/ims/copy/IBSHIST.cpy");
        for (String f : List.of("TXID", "TIMESTMP", "TRANSTYP", "AMOUNT", "REFTXID", "ACCID")) {
            assertEquals(g.item(f).pic(), s.item(f).pic(), f);
            assertEquals(g.item(f).usage(), s.item(f).usage(), f);
        }
        // IBTRAN.cpy ACCOUNT-SUMMARY balance uses the same packed picture as HISTORY.AMOUNT.
        CopybookParser.Item bal = CopybookParser.read("src/base/ims/copy/IBTRAN.cpy").item("BALANCE-AS");
        assertNotNull(bal.pic());
        assertTrue(PicClause.parse(bal.pic()).sameShape(PicClause.parse(BankTransactionRecord.PIC_AMOUNT)));
        assertEquals(Usage.COMP_3, bal.usage());
    }
}
