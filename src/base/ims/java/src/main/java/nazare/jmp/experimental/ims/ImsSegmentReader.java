package nazare.jmp.experimental.ims;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Read-only access to the three root-only IMS databases used by IBACSUM and IBLOGIN.
 * "Not found" (DL/I GE/GB) is an empty result; any other failure is an {@link SQLException}.
 */
public interface ImsSegmentReader {

    /** GU CUSTOMER(CUSTID = custId). */
    Optional<CustomerSegment> findCustomer(int custId) throws SQLException;

    /** GHU then GHN CUSTACCS(CUSTID = custId) until GE/GB, in database (not key) order. */
    List<CustomerAccountSegment> findCustomerAccounts(int custId) throws SQLException;

    /** GU ACCOUNT(ACCID = accId). */
    Optional<AccountSegment> findAccount(long accId) throws SQLException;
}
