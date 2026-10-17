package nazare.jmp.experimental.ims;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import nazare.jmp.experimental.ExperimentalFeatures;

/**
 * Read-only equivalent of IBACSUM GET-ACCOUNT-SUMMARY ({@code src/base/ims/cobol/IBACSUM.cbl}).
 *
 * <p>Reproduces the COBOL loop: walk CUSTACCS for the customer, look each ACCOUNT up, and stop at the
 * first ACCOUNT that is missing. A missing account after at least one hit ends the list quietly
 * with outcome OK; it is NO_ACCOUNT only when it is the first one.
 */
public final class AccountSummaryQuery {

    public static final int MAX_ACCOUNTS = 6;
    public static final String SUCCESS_MESSAGE = "0".repeat(32);
    public static final String NO_CUSTOMER = "CUSTOMER DOES NOT EXIST";
    public static final String NO_ACCOUNT = "ACCOUNT DOES NOT EXIST";
    public static final String BAD_STATUS = "BAD STATUS CODE WAS RECEIVED: ";

    private final ImsSegmentReader reader;

    public AccountSummaryQuery(ImsSegmentReader reader) {
        ExperimentalFeatures.requireImsReadOnly();
        this.reader = reader;
    }

    public AccountSummary summarize(int custId) {
        List<AccountSegment> accounts = new ArrayList<>();
        try {
            List<CustomerAccountSegment> links = reader.findCustomerAccounts(custId);
            if (links.isEmpty()) {
                return new AccountSummary(AccountSummary.Outcome.NO_CUSTOMER, NO_CUSTOMER, accounts);
            }
            for (CustomerAccountSegment link : links) {
                Optional<AccountSegment> account = reader.findAccount(link.accId());
                if (account.isEmpty()) {
                    if (accounts.isEmpty()) {
                        return new AccountSummary(AccountSummary.Outcome.NO_ACCOUNT, NO_ACCOUNT, accounts);
                    }
                    break;
                }
                if (accounts.size() == MAX_ACCOUNTS) {
                    return new AccountSummary(AccountSummary.Outcome.MORE_THAN_SIX_ACCOUNTS,
                            "MORE THAN " + MAX_ACCOUNTS + " ACCOUNTS", accounts);
                }
                accounts.add(account.get());
            }
            return new AccountSummary(AccountSummary.Outcome.OK, SUCCESS_MESSAGE, accounts);
        } catch (SQLException e) {
            return new AccountSummary(AccountSummary.Outcome.BAD_STATUS, BAD_STATUS + statusOf(e), accounts);
        }
    }

    private static String statusOf(SQLException e) {
        String state = e.getSQLState();
        return state == null ? "??" : state.substring(0, Math.min(2, state.length()));
    }
}
