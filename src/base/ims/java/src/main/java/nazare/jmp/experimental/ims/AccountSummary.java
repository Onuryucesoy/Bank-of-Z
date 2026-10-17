package nazare.jmp.experimental.ims;

import java.util.List;

/** Result of {@link AccountSummaryQuery}, mirroring IBACSUM's OUTPUT-AREA. */
public record AccountSummary(Outcome outcome, String message, List<AccountSegment> accounts) {

    public enum Outcome {
        /** At least one account returned. MSG-OUT is 32 EBCDIC '0' characters (from MOVE ZEROS TO OUTPUT-AREA). */
        OK,
        /** No CUSTACCS segment for the customer: "CUSTOMER DOES NOT EXIST". */
        NO_CUSTOMER,
        /** The first CUSTACCS link points to a missing ACCOUNT: "ACCOUNT DOES NOT EXIST". */
        NO_ACCOUNT,
        /** Any other DL/I status: "BAD STATUS CODE WAS RECEIVED: xx". */
        BAD_STATUS,
        /**
         * More than 6 accounts. IBACSUM's OCCURS 1 TO 6 table would overflow (undefined behaviour);
         * the Java side refuses instead. Needs a product decision before cut-over.
         */
        MORE_THAN_SIX_ACCOUNTS
    }

    public AccountSummary {
        accounts = List.copyOf(accounts);
    }
}
