package nazare.jmp.commarea;

import java.util.Arrays;

import nazare.jmp.cobol.CobolDate;
import nazare.jmp.cobol.Ebcdic;
import nazare.jmp.cobol.Money;
import nazare.jmp.cobol.PicSpec;
import nazare.jmp.cobol.ZonedDecimal;

/**
 * Converts {@link ProcIsrtCommarea} records to and from the EBCDIC (Cp1047) byte image of
 * {@code PROCISRT-COMMAREA}, assuming 31-bit addressing (4-byte POINTER) and no SYNC padding.
 *
 * <pre>
 * offset len  field
 *      0   1  PROCISRT-FUNCTION        PIC X
 *      1   4  PROCISRT-PCB-POINTER     POINTER   (written as X'00000000', ignored on read)
 *      5 126  PROCISRT-DEBIT-STRUCT and its REDEFINES (longest member, incl. FILLER X(100))
 * </pre>
 *
 * Offsets inside the 126-byte area (relative to offset 5):
 * <pre>
 *   all       0  8 ACCNO 9(8)        8  6 SORTCODE 9(6)      14 12 AMOUNT/BALANCE S9(10)V99
 *   '3'      26  8 TARGET-ACCNO 9(8)
 *   '4','5'  26  8 DOB yyyy|mm|dd     34 60 NAME X(60)        94 10 NUMBER 9(10)
 *   '6','7'  26  8 L-STMNT yyyy|mm|dd 34  8 N-STMNT           42  8 TYPE X(8)   50 10 CUSTNO 9(10)
 * </pre>
 * Bytes a layout does not define are written as EBCDIC spaces and ignored on read, so residue
 * left by a previous REDEFINES member is never part of the contract.
 */
public final class ProcIsrtCodec {

    public static final int POINTER_LENGTH = 4;
    public static final int STRUCT_OFFSET = 1 + POINTER_LENGTH;
    public static final int STRUCT_LENGTH = 8 + 6 + 12 + 100;
    public static final int LENGTH = STRUCT_OFFSET + STRUCT_LENGTH;

    static final int ACCNO = 0;
    static final int SORTCODE = 8;
    static final int AMOUNT = 14;
    static final int XFR_TARGET = 26;
    static final int CUST_DOB = 26;
    static final int CUST_NAME = 34;
    static final int CUST_NUMBER = 94;
    static final int ACC_LAST_STMT = 26;
    static final int ACC_NEXT_STMT = 34;
    static final int ACC_TYPE = 42;
    static final int ACC_CUSTNO = 50;

    private ProcIsrtCodec() {
    }

    public static byte[] encode(ProcIsrtCommarea commarea) {
        byte[] out = new byte[LENGTH];
        Arrays.fill(out, STRUCT_OFFSET, LENGTH, Ebcdic.SPACE);
        out[0] = String.valueOf(commarea.function().code()).getBytes(Ebcdic.CP1047)[0];
        switch (commarea) {
            case ProcIsrtCommarea.Debit d -> key(out, d.accountNumber(), d.sortCode(), d.amount());
            case ProcIsrtCommarea.Credit c -> key(out, c.accountNumber(), c.sortCode(), c.amount());
            case ProcIsrtCommarea.TransferLocal x -> {
                key(out, x.accountNumber(), x.sortCode(), x.amount());
                ZonedDecimal.encodeUnsigned(x.targetAccountNumber(), 8, out, at(XFR_TARGET));
            }
            case ProcIsrtCommarea.DeleteCustomer d ->
                    customer(out, d.accountNumber(), d.sortCode(), d.balance(), d.dateOfBirth(), d.name(), d.customerNumber());
            case ProcIsrtCommarea.CreateCustomer c ->
                    customer(out, c.accountNumber(), c.sortCode(), c.balance(), c.dateOfBirth(), c.name(), c.customerNumber());
            case ProcIsrtCommarea.DeleteAccount d -> account(out, d.accountNumber(), d.sortCode(), d.balance(),
                    d.lastStatement(), d.nextStatement(), d.accountType(), d.customerNumber());
            case ProcIsrtCommarea.CreateAccount c -> account(out, c.accountNumber(), c.sortCode(), c.balance(),
                    c.lastStatement(), c.nextStatement(), c.accountType(), c.customerNumber());
        }
        return out;
    }

    public static ProcIsrtCommarea decode(byte[] in) {
        if (in.length != LENGTH) {
            throw new IllegalArgumentException("PROCISRT-COMMAREA is " + LENGTH + " bytes, got " + in.length);
        }
        FunctionCode function = FunctionCode.fromCode(new String(in, 0, 1, Ebcdic.CP1047).charAt(0));
        long accno = ZonedDecimal.decodeUnsigned(in, at(ACCNO), 8);
        int sortCode = (int) ZonedDecimal.decodeUnsigned(in, at(SORTCODE), 6);
        Money amount = Money.of(ZonedDecimal.decode(in, at(AMOUNT), PicSpec.S9_10_V99));
        return switch (function) {
            case DEBIT -> new ProcIsrtCommarea.Debit(accno, sortCode, amount);
            case CREDIT -> new ProcIsrtCommarea.Credit(accno, sortCode, amount);
            case XFR_LOCAL -> new ProcIsrtCommarea.TransferLocal(accno, sortCode, amount,
                    ZonedDecimal.decodeUnsigned(in, at(XFR_TARGET), 8));
            case DELETE_CUSTOMER -> new ProcIsrtCommarea.DeleteCustomer(accno, sortCode, amount,
                    readDate(in, CUST_DOB), Ebcdic.readAlphanumeric(in, at(CUST_NAME), ProcIsrtCommarea.NAME_LENGTH),
                    ZonedDecimal.decodeUnsigned(in, at(CUST_NUMBER), 10));
            case CREATE_CUSTOMER -> new ProcIsrtCommarea.CreateCustomer(accno, sortCode, amount,
                    readDate(in, CUST_DOB), Ebcdic.readAlphanumeric(in, at(CUST_NAME), ProcIsrtCommarea.NAME_LENGTH),
                    ZonedDecimal.decodeUnsigned(in, at(CUST_NUMBER), 10));
            case DELETE_ACCOUNT -> new ProcIsrtCommarea.DeleteAccount(accno, sortCode, amount,
                    readDate(in, ACC_LAST_STMT), readDate(in, ACC_NEXT_STMT),
                    Ebcdic.readAlphanumeric(in, at(ACC_TYPE), ProcIsrtCommarea.ACCOUNT_TYPE_LENGTH),
                    ZonedDecimal.decodeUnsigned(in, at(ACC_CUSTNO), 10));
            case CREATE_ACCOUNT -> new ProcIsrtCommarea.CreateAccount(accno, sortCode, amount,
                    readDate(in, ACC_LAST_STMT), readDate(in, ACC_NEXT_STMT),
                    Ebcdic.readAlphanumeric(in, at(ACC_TYPE), ProcIsrtCommarea.ACCOUNT_TYPE_LENGTH),
                    ZonedDecimal.decodeUnsigned(in, at(ACC_CUSTNO), 10));
        };
    }

    private static void key(byte[] out, long accno, int sortCode, Money amount) {
        ZonedDecimal.encodeUnsigned(accno, 8, out, at(ACCNO));
        ZonedDecimal.encodeUnsigned(sortCode, 6, out, at(SORTCODE));
        ZonedDecimal.encode(amount.toBigDecimal(), PicSpec.S9_10_V99, out, at(AMOUNT));
    }

    private static void customer(byte[] out, long accno, int sortCode, Money balance, CobolDate dob,
                                 String name, long number) {
        key(out, accno, sortCode, balance);
        writeDate(out, CUST_DOB, dob);
        Ebcdic.writeAlphanumeric(name, out, at(CUST_NAME), ProcIsrtCommarea.NAME_LENGTH);
        ZonedDecimal.encodeUnsigned(number, 10, out, at(CUST_NUMBER));
    }

    private static void account(byte[] out, long accno, int sortCode, Money balance, CobolDate last,
                                CobolDate next, String type, long custno) {
        key(out, accno, sortCode, balance);
        writeDate(out, ACC_LAST_STMT, last);
        writeDate(out, ACC_NEXT_STMT, next);
        Ebcdic.writeAlphanumeric(type, out, at(ACC_TYPE), ProcIsrtCommarea.ACCOUNT_TYPE_LENGTH);
        ZonedDecimal.encodeUnsigned(custno, 10, out, at(ACC_CUSTNO));
    }

    private static void writeDate(byte[] out, int rel, CobolDate date) {
        ZonedDecimal.encodeUnsigned(date.year(), 4, out, at(rel));
        ZonedDecimal.encodeUnsigned(date.month(), 2, out, at(rel + 4));
        ZonedDecimal.encodeUnsigned(date.day(), 2, out, at(rel + 6));
    }

    private static CobolDate readDate(byte[] in, int rel) {
        return new CobolDate((int) ZonedDecimal.decodeUnsigned(in, at(rel), 4),
                (int) ZonedDecimal.decodeUnsigned(in, at(rel + 4), 2),
                (int) ZonedDecimal.decodeUnsigned(in, at(rel + 6), 2));
    }

    private static int at(int relative) {
        return STRUCT_OFFSET + relative;
    }
}
