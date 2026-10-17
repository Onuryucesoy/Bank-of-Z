package nazare.jmp.commarea;

import nazare.jmp.cobol.CobolDate;
import nazare.jmp.cobol.Money;
import nazare.jmp.cobol.PicSpec;

/**
 * Typed model of {@code PROCISRT-COMMAREA} ({@code src/base/cics/copy/PROCISRT.cpy}).
 *
 * <p>The copybook overlays seven layouts on one storage area with REDEFINES. Here each function
 * code has its own record with only the fields that layout defines; {@link ProcIsrtCodec} maps
 * them to and from the 131-byte EBCDIC image. {@code PROCISRT-PCB-POINTER} is not modelled: it
 * is an address inside the CICS region and has no meaning off-platform.
 *
 * <p>Constructors reject values that do not fit their PICTURE instead of truncating them.
 */
public sealed interface ProcIsrtCommarea {

    long MAX_ACCOUNT_NUMBER = 99_999_999L;
    int MAX_SORT_CODE = 999_999;
    long MAX_CUSTOMER_NUMBER = 9_999_999_999L;
    int NAME_LENGTH = 60;
    int ACCOUNT_TYPE_LENGTH = 8;

    FunctionCode function();

    /** Function '1'. {@code PROCISRT-DEBIT-STRUCT}. */
    record Debit(long accountNumber, int sortCode, Money amount) implements ProcIsrtCommarea {
        public Debit {
            validateKeyAndAmount(accountNumber, sortCode, amount);
        }

        public FunctionCode function() {
            return FunctionCode.DEBIT;
        }
    }

    /** Function '2'. {@code PROCISRT-CREDIT-STRUCT}. */
    record Credit(long accountNumber, int sortCode, Money amount) implements ProcIsrtCommarea {
        public Credit {
            validateKeyAndAmount(accountNumber, sortCode, amount);
        }

        public FunctionCode function() {
            return FunctionCode.CREDIT;
        }
    }

    /** Function '3'. {@code PROCISRT-XFR-LOCAL-STRUCT}: the target account shares the source sort code. */
    record TransferLocal(long accountNumber, int sortCode, Money amount, long targetAccountNumber)
            implements ProcIsrtCommarea {
        public TransferLocal {
            validateKeyAndAmount(accountNumber, sortCode, amount);
            range(targetAccountNumber, MAX_ACCOUNT_NUMBER, "targetAccountNumber");
        }

        public FunctionCode function() {
            return FunctionCode.XFR_LOCAL;
        }
    }

    /** Function '4'. {@code PROCISRT-DELETE-CUST-STRUCT}. */
    record DeleteCustomer(long accountNumber, int sortCode, Money balance, CobolDate dateOfBirth,
                          String name, long customerNumber) implements ProcIsrtCommarea {
        public DeleteCustomer {
            validateCustomer(accountNumber, sortCode, balance, dateOfBirth, name, customerNumber);
        }

        public FunctionCode function() {
            return FunctionCode.DELETE_CUSTOMER;
        }
    }

    /** Function '5'. {@code PROCISRT-CREATE-CUST-STRUCT}. */
    record CreateCustomer(long accountNumber, int sortCode, Money balance, CobolDate dateOfBirth,
                          String name, long customerNumber) implements ProcIsrtCommarea {
        public CreateCustomer {
            validateCustomer(accountNumber, sortCode, balance, dateOfBirth, name, customerNumber);
        }

        public FunctionCode function() {
            return FunctionCode.CREATE_CUSTOMER;
        }
    }

    /** Function '6'. {@code PROCISRT-DELACC-STRUCT}. */
    record DeleteAccount(long accountNumber, int sortCode, Money balance, CobolDate lastStatement,
                         CobolDate nextStatement, String accountType, long customerNumber)
            implements ProcIsrtCommarea {
        public DeleteAccount {
            validateAccount(accountNumber, sortCode, balance, lastStatement, nextStatement, accountType, customerNumber);
        }

        public FunctionCode function() {
            return FunctionCode.DELETE_ACCOUNT;
        }
    }

    /** Function '7'. {@code PROCISRT-CREACC-STRUCT}. */
    record CreateAccount(long accountNumber, int sortCode, Money balance, CobolDate lastStatement,
                         CobolDate nextStatement, String accountType, long customerNumber)
            implements ProcIsrtCommarea {
        public CreateAccount {
            validateAccount(accountNumber, sortCode, balance, lastStatement, nextStatement, accountType, customerNumber);
        }

        public FunctionCode function() {
            return FunctionCode.CREATE_ACCOUNT;
        }
    }

    private static void validateKeyAndAmount(long accountNumber, int sortCode, Money amount) {
        range(accountNumber, MAX_ACCOUNT_NUMBER, "accountNumber");
        range(sortCode, MAX_SORT_CODE, "sortCode");
        if (amount == null) {
            throw new IllegalArgumentException("amount/balance is required");
        }
        amount.requireFits(PicSpec.S9_10_V99);
    }

    private static void validateCustomer(long accountNumber, int sortCode, Money balance, CobolDate dob,
                                         String name, long customerNumber) {
        validateKeyAndAmount(accountNumber, sortCode, balance);
        required(dob, "dateOfBirth");
        text(name, NAME_LENGTH, "name");
        range(customerNumber, MAX_CUSTOMER_NUMBER, "customerNumber");
    }

    private static void validateAccount(long accountNumber, int sortCode, Money balance, CobolDate last,
                                        CobolDate next, String type, long customerNumber) {
        validateKeyAndAmount(accountNumber, sortCode, balance);
        required(last, "lastStatement");
        required(next, "nextStatement");
        text(type, ACCOUNT_TYPE_LENGTH, "accountType");
        range(customerNumber, MAX_CUSTOMER_NUMBER, "customerNumber");
    }

    private static void range(long value, long max, String name) {
        if (value < 0 || value > max) {
            throw new IllegalArgumentException(name + " " + value + " is outside 0.." + max);
        }
    }

    private static void required(Object value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    private static void text(String value, int length, String name) {
        required(value, name);
        if (value.length() > length) {
            throw new IllegalArgumentException(name + " is longer than PIC X(" + length + ")");
        }
        if (!value.isEmpty() && value.charAt(value.length() - 1) == ' ') {
            throw new IllegalArgumentException(name + " must not have trailing spaces; the field is space-padded");
        }
    }
}
