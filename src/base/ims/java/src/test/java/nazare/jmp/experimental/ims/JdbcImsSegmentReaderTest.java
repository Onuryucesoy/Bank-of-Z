package nazare.jmp.experimental.ims;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import nazare.jmp.cobol.Money;

/**
 * Exercises the SQL and column mapping against H2 tables named like the IMS JDBC catalog view
 * (PCB.SEGMENT). This proves the Java mapping, not IMS SQL dialect compatibility.
 */
@ExtendWith(ExperimentalFlag.class)
class JdbcImsSegmentReaderTest {

    private Connection connection;
    private JdbcImsSegmentReader reader;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:h2:mem:ims" + System.nanoTime());
        try (Statement st = connection.createStatement()) {
            st.execute("CREATE SCHEMA CUSTOMER");
            st.execute("CREATE SCHEMA CUSTACCS");
            st.execute("CREATE SCHEMA ACCOUNT");
            st.execute("CREATE TABLE CUSTOMER.CUSTOMER (CUSTID INT PRIMARY KEY, LASTNAME CHAR(50), FIRSTNAME CHAR(50), "
                    + "ADDRESS CHAR(80), CITY CHAR(25), STATE CHAR(2), ZIPCODE CHAR(15), PHONE CHAR(12), STATUS CHAR(1), "
                    + "PASSWORD CHAR(16), CUSTOMERTYPE CHAR(1), LASTLOGIN CHAR(23))");
            st.execute("CREATE TABLE CUSTACCS.CUSTOMERACCS (CUSTID INT, ACCID BIGINT, ACCNUM INT)");
            st.execute("CREATE TABLE ACCOUNT.ACCOUNT (ACCID BIGINT PRIMARY KEY, ACCTYPE CHAR(1), BALANCE DECIMAL(15,2), "
                    + "LASTTXID BIGINT)");
            // Rows taken from src/base/ims/LoadData
            st.execute("INSERT INTO CUSTOMER.CUSTOMER VALUES (1, 'Antonelli', 'Martha', '922 East Meighan Boulevard', "
                    + "'ART', 'TX', '76820', '914-513-5089', '0', 'password', 'c', '2003-07-23 17:56:00.64')");
            st.execute("INSERT INTO CUSTACCS.CUSTOMERACCS VALUES (1, 101, 1), (1, 102, 2), (2, 201, 1)");
            st.execute("INSERT INTO ACCOUNT.ACCOUNT VALUES (101, 'c', 8830.00, 1), (102, 's', 6325.00, 1)");
        }
        reader = new JdbcImsSegmentReader(connection, connection);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    @Test
    void readsCustomer() throws Exception {
        CustomerSegment c = reader.findCustomer(1).orElseThrow();
        assertEquals(1, c.custId());
        assertEquals("Antonelli", c.lastName().strip());
        assertEquals("0", c.status());
        assertEquals("c", c.customerType());
        assertTrue(reader.findCustomer(2).isEmpty());
    }

    @Test
    void readsLinksAndAccounts() throws Exception {
        List<CustomerAccountSegment> links = reader.findCustomerAccounts(1);
        assertEquals(2, links.size());
        AccountSegment a = reader.findAccount(101).orElseThrow();
        assertEquals(Money.of("8830.00"), a.balance());
        assertEquals("c", a.accType());
        assertTrue(reader.findAccount(999).isEmpty());
    }

    @Test
    void summaryOverJdbc() {
        AccountSummaryQuery q = new AccountSummaryQuery(reader);
        AccountSummary ok = q.summarize(1);
        assertEquals(AccountSummary.Outcome.OK, ok.outcome());
        assertEquals(2, ok.accounts().size());
        assertEquals(AccountSummary.Outcome.NO_ACCOUNT, q.summarize(2).outcome(), "link 201 has no ACCOUNT row");
        assertEquals(AccountSummary.Outcome.NO_CUSTOMER, q.summarize(3).outcome());
    }
}
