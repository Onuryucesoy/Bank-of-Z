package nazare.jmp.experimental.ims;

import java.sql.SQLException;
import java.util.Optional;

import nazare.jmp.experimental.ExperimentalFeatures;

/**
 * Read-only part of IBLOGIN ({@code src/base/ims/pli/IBLOGIN.pli}, procedure LOGIN): the same checks in
 * the same order, without the REPL that marks the customer logged in.
 */
public final class LoginCheck {

    public static final String LOGIN_SUCCESSFUL = "LOGIN SUCCESSFUL";
    public static final String CUSTOMER_LOGGED_IN = "CUSTOMER ALREADY LOGGED IN";
    public static final String PASSWORD_INVALID = "PASSWORD INVALID";
    public static final String NO_CUSTOMER = "CUSTOMER DOES NOT EXIST";
    static final int PASSWORD_LENGTH = 16;

    public enum Decision {
        /** GHU returned GE or GB. */
        NO_CUSTOMER,
        /** Checked before the logged-in flag, so a wrong password wins over "already logged in". */
        PASSWORD_INVALID,
        /** STATUS = '1'. */
        ALREADY_LOGGED_IN,
        /** IBLOGIN would now REPL STATUS='1' and LASTLOGIN and answer "LOGIN SUCCESSFUL". Not done here. */
        WOULD_LOG_IN
    }

    private final ImsSegmentReader reader;

    public LoginCheck(ImsSegmentReader reader) {
        ExperimentalFeatures.requireImsReadOnly();
        this.reader = reader;
    }

    public Decision check(int custId, String password) throws SQLException {
        return decide(reader.findCustomer(custId), password);
    }

    /** Pure decision logic. Passwords are compared as CHAR(16) values: right-padded with spaces. */
    public static Decision decide(Optional<CustomerSegment> customer, String password) {
        if (customer.isEmpty()) {
            return Decision.NO_CUSTOMER;
        }
        CustomerSegment c = customer.get();
        if (!asChar16(c.password()).equals(asChar16(password))) {
            return Decision.PASSWORD_INVALID;
        }
        if (CustomerSegment.LOGGED_IN.equals(c.status())) {
            return Decision.ALREADY_LOGGED_IN;
        }
        return Decision.WOULD_LOG_IN;
    }

    public static String message(Decision decision) {
        return switch (decision) {
            case NO_CUSTOMER -> NO_CUSTOMER;
            case PASSWORD_INVALID -> PASSWORD_INVALID;
            case ALREADY_LOGGED_IN -> CUSTOMER_LOGGED_IN;
            case WOULD_LOG_IN -> LOGIN_SUCCESSFUL;
        };
    }

    private static String asChar16(String value) {
        String v = value == null ? "" : value;
        if (v.length() > PASSWORD_LENGTH) {
            v = v.substring(0, PASSWORD_LENGTH);
        }
        return v + " ".repeat(PASSWORD_LENGTH - v.length());
    }
}
