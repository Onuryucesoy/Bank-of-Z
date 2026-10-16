package nazare.jmp.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import nazare.jmp.model.Customer;
import nazare.jmp.model.SortCode;

class CustomerRepositoryTest {

	private Connection connection;
	private CustomerRepository repository;

	@BeforeEach
	void setUp() throws SQLException {
		connection = H2Db2.open();
		repository = new CustomerRepository(connection, "BANKZ");
	}

	@AfterEach
	void tearDown() throws SQLException {
		connection.close();
	}

	@Test
	void insertThenFindByKeyRoundTripsAllFields() throws SQLException {
		Customer customer = customer(42);
		repository.insert(customer);

		Customer found = repository.findByKey(SortCode.VALUE, 42).orElseThrow();

		assertEquals(customer, found);
		assertTrue(found.isActive());
	}

	@Test
	void storesKeysZeroPaddedAndDatesAsYyyymmddIntegers() throws SQLException {
		repository.insert(customer(42));

		try (Statement st = connection.createStatement();
				ResultSet rs = st.executeQuery("SELECT CUSTOMER_NUMBER, CUSTOMER_DATE_OF_BIRTH, "
						+ "CUSTOMER_CREATED_DATE, CUSTOMER_CS_REVIEW_DATE FROM BANKZ.CUSTOMER")) {
			assertTrue(rs.next());
			assertEquals("0000000042", rs.getString(1));
			assertEquals(19800115, rs.getInt(2));
			assertEquals(20230301, rs.getInt(3));
			assertEquals(20231231, rs.getInt(4));
		}
	}

	@Test
	void readsRowsWrittenByCobolWithBlankPaddingAndZeroDates() throws SQLException {
		try (Statement st = connection.createStatement()) {
			st.executeUpdate("INSERT INTO BANKZ.CUSTOMER VALUES ('CUST', '987654', '0000000007', 'Mr', 'Joe', "
					+ "'Bloggs', 19700101, '0123', 'Line 1', '', 'Leeds', 'LS1', 'UK', 'ACTIVE', 0, 500, NULL)");
		}

		Customer found = repository.findByKey(SortCode.VALUE, 7).orElseThrow();

		assertEquals("Mr", found.getTitle());
		assertEquals("Bloggs", found.getLastName());
		assertEquals("", found.getAddressLine2());
		assertEquals(LocalDate.of(1970, 1, 1), found.getDateOfBirth());
		assertNull(found.getCreatedDate());
		assertNull(found.getCreditScoreReviewDate());
		assertEquals(500, found.getCreditScore());
	}

	@Test
	void invalidStoredDateIsReportedAsSqlException() throws SQLException {
		try (Statement st = connection.createStatement()) {
			st.executeUpdate("INSERT INTO BANKZ.CUSTOMER (CUSTOMER_SORTCODE, CUSTOMER_NUMBER, CUSTOMER_DATE_OF_BIRTH) "
					+ "VALUES ('987654', '0000000008', 20230231)");
		}

		assertThrows(SQLException.class, () -> repository.findByKey(SortCode.VALUE, 8));
	}

	@Test
	void findByKeyReturnsEmptyWhenMissingOrDifferentSortcode() throws SQLException {
		repository.insert(customer(42));

		assertTrue(repository.findByKey(SortCode.VALUE, 43).isEmpty());
		assertTrue(repository.findByKey("123456", 42).isEmpty());
	}

	@Test
	void findLastReturnsHighestCustomerNumberForSortcode() throws SQLException {
		repository.insert(customer(5));
		repository.insert(customer(120));
		repository.insert(customer(33));
		Customer otherBank = customer(999);
		otherBank.setSortcode("111111");
		repository.insert(otherBank);

		assertEquals(120, repository.findLast(SortCode.VALUE).orElseThrow().getNumber());
		assertTrue(repository.findLast("222222").isEmpty());
	}

	@Test
	void updateChangesOnlyUpdcustColumns() throws SQLException {
		Customer original = customer(42);
		repository.insert(original);

		Customer change = customer(42);
		change.setLastName("Smith");
		change.setCity("York");
		change.setStatus(Customer.STATUS_SUSPENDED);
		change.setCreditScore(1);
		change.setCreatedDate(LocalDate.of(2000, 1, 1));
		change.setCreditScoreReviewDate(LocalDate.of(2000, 1, 1));
		assertTrue(repository.update(change));

		Customer found = repository.findByKey(SortCode.VALUE, 42).orElseThrow();
		assertEquals("Smith", found.getLastName());
		assertEquals("York", found.getCity());
		assertEquals(Customer.STATUS_SUSPENDED, found.getStatus());
		assertEquals(original.getCreditScore(), found.getCreditScore());
		assertEquals(original.getCreatedDate(), found.getCreatedDate());
		assertEquals(original.getCreditScoreReviewDate(), found.getCreditScoreReviewDate());
	}

	@Test
	void updateAndDeleteReturnFalseWhenMissing() throws SQLException {
		assertFalse(repository.update(customer(42)));
		assertFalse(repository.delete(SortCode.VALUE, 42));
	}

	@Test
	void deleteRemovesCustomer() throws SQLException {
		repository.insert(customer(42));

		assertTrue(repository.delete(SortCode.VALUE, 42));
		assertTrue(repository.findByKey(SortCode.VALUE, 42).isEmpty());
	}

	@Test
	void rejectsValuesThatDoNotFitTheColumns() {
		Customer longTitle = customer(1);
		longTitle.setTitle("Professor Dr");
		assertThrows(IllegalArgumentException.class, () -> repository.insert(longTitle));

		assertThrows(IllegalArgumentException.class, () -> repository.insert(customer(Customer.MAX_NUMBER + 1)));
		assertThrows(IllegalArgumentException.class, () -> repository.insert(customer(-1)));

		Customer badScore = customer(1);
		badScore.setCreditScore(1000);
		assertThrows(IllegalArgumentException.class, () -> repository.insert(badScore));

		assertThrows(IllegalArgumentException.class, () -> repository.findByKey("98765", 1));
		assertThrows(IllegalArgumentException.class, () -> repository.findByKey("98765A", 1));
	}

	@Test
	void acceptsMaximumCustomerNumber() throws SQLException {
		repository.insert(customer(Customer.MAX_NUMBER));

		assertTrue(repository.findByKey(SortCode.VALUE, Customer.MAX_NUMBER).isPresent());
	}

	@Test
	void unqualifiedRepositoryUsesCurrentSchema() throws SQLException {
		try (Statement st = connection.createStatement()) {
			st.execute("SET SCHEMA BANKZ");
		}
		CustomerRepository unqualified = new CustomerRepository(connection);
		unqualified.insert(customer(42));

		assertTrue(repository.findByKey(SortCode.VALUE, 42).isPresent());
	}

	@Test
	void rejectsInvalidSchemaName() {
		assertThrows(IllegalArgumentException.class, () -> new CustomerRepository(connection, "BANKZ; DROP"));
	}

	static Customer customer(long number) {
		Customer c = new Customer(SortCode.VALUE, number);
		c.setTitle("Dr");
		c.setFirstName("Ada");
		c.setLastName("Lovelace");
		c.setDateOfBirth(LocalDate.of(1980, 1, 15));
		c.setPhone("01234 567890");
		c.setAddressLine1("1 High Street");
		c.setAddressLine2("Flat 2");
		c.setCity("London");
		c.setPostcode("N1 9GU");
		c.setCountry("UK");
		c.setStatus(Customer.STATUS_ACTIVE);
		c.setCreatedDate(LocalDate.of(2023, 3, 1));
		c.setCreditScore(720);
		c.setCreditScoreReviewDate(LocalDate.of(2023, 12, 31));
		return c;
	}
}
