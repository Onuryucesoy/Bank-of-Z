package nazare.jmp.experimental.ims;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import nazare.jmp.cobol.Money;
import nazare.jmp.experimental.ExperimentalFeatures;

/**
 * {@link ImsSegmentReader} over IMS Universal JDBC connections. Issues SELECT statements only.
 *
 * <p>SQL names follow the IMS JDBC convention {@code <PCB name>.<segment>} with DBD external names
 * where defined (CUSTOMERACCS, FIRSTNAME, CUSTOMERTYPE, LASTLOGIN). These names have not been run
 * against a live IMS catalog yet; verify them before the first parallel run.
 *
 * <p>Pass a connection opened on the {@code IBGCUDAT} PSB for CUSTOMER and one on {@code IBACSUM}
 * for CUSTACCS/ACCOUNT. Both PSBs have PROCOPT=G, so IMS itself refuses updates. Do not use the
 * IBLOGIN PSB (PROCOPT=R allows REPL). The caller owns and closes the connections.
 */
public final class JdbcImsSegmentReader implements ImsSegmentReader {

    static final String CUSTOMER_SQL = "SELECT CUSTID, LASTNAME, FIRSTNAME, ADDRESS, CITY, STATE, ZIPCODE, PHONE, "
            + "STATUS, PASSWORD, CUSTOMERTYPE, LASTLOGIN FROM CUSTOMER.CUSTOMER WHERE CUSTID = ?";
    static final String CUSTACCS_SQL = "SELECT CUSTID, ACCID, ACCNUM FROM CUSTACCS.CUSTOMERACCS WHERE CUSTID = ?";
    static final String ACCOUNT_SQL = "SELECT ACCID, ACCTYPE, BALANCE, LASTTXID FROM ACCOUNT.ACCOUNT WHERE ACCID = ?";

    private final Connection customerConnection;
    private final Connection accountConnection;

    public JdbcImsSegmentReader(Connection customerConnection, Connection accountConnection) {
        ExperimentalFeatures.requireImsReadOnly();
        this.customerConnection = customerConnection;
        this.accountConnection = accountConnection;
    }

    @Override
    public Optional<CustomerSegment> findCustomer(int custId) throws SQLException {
        try (PreparedStatement ps = customerConnection.prepareStatement(CUSTOMER_SQL)) {
            ps.setInt(1, custId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new CustomerSegment(rs.getInt("CUSTID"), rs.getString("LASTNAME"),
                        rs.getString("FIRSTNAME"), rs.getString("ADDRESS"), rs.getString("CITY"),
                        rs.getString("STATE"), rs.getString("ZIPCODE"), rs.getString("PHONE"),
                        rs.getString("STATUS"), rs.getString("PASSWORD"), rs.getString("CUSTOMERTYPE"),
                        rs.getString("LASTLOGIN")));
            }
        }
    }

    @Override
    public List<CustomerAccountSegment> findCustomerAccounts(int custId) throws SQLException {
        List<CustomerAccountSegment> links = new ArrayList<>();
        try (PreparedStatement ps = accountConnection.prepareStatement(CUSTACCS_SQL)) {
            ps.setInt(1, custId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    links.add(new CustomerAccountSegment(rs.getInt("CUSTID"), rs.getLong("ACCID"), rs.getInt("ACCNUM")));
                }
            }
        }
        return links;
    }

    @Override
    public Optional<AccountSegment> findAccount(long accId) throws SQLException {
        try (PreparedStatement ps = accountConnection.prepareStatement(ACCOUNT_SQL)) {
            ps.setLong(1, accId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new AccountSegment(rs.getLong("ACCID"), rs.getString("ACCTYPE"),
                        Money.of(rs.getBigDecimal("BALANCE")), rs.getLong("LASTTXID")));
            }
        }
    }
}
