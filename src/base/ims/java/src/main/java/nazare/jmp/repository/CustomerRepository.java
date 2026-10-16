package nazare.jmp.repository;

import static nazare.jmp.repository.JdbcColumns.formatNumber;
import static nazare.jmp.repository.JdbcColumns.getChar;
import static nazare.jmp.repository.JdbcColumns.getInteger;
import static nazare.jmp.repository.JdbcColumns.getYyyymmdd;
import static nazare.jmp.repository.JdbcColumns.parseNumber;
import static nazare.jmp.repository.JdbcColumns.requireSortcode;
import static nazare.jmp.repository.JdbcColumns.setChar;
import static nazare.jmp.repository.JdbcColumns.setInteger;
import static nazare.jmp.repository.JdbcColumns.setYyyymmdd;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Objects;
import java.util.Optional;

import nazare.jmp.model.Customer;

/**
 * JDBC access to the Db2 {@code CUSTOMER} table ({@code CUSTDB2}), mirroring the SQL in INQCUST, CRECUST,
 * UPDCUST and DELCUS. The caller owns the connection and its transaction.
 */
public class CustomerRepository {

	private static final String COLUMNS = "CUSTOMER_EYECATCHER, CUSTOMER_SORTCODE, CUSTOMER_NUMBER, "
			+ "CUSTOMER_TITLE, CUSTOMER_FIRST_NAME, CUSTOMER_LAST_NAME, CUSTOMER_DATE_OF_BIRTH, CUSTOMER_PHONE, "
			+ "CUSTOMER_ADDR_LINE1, CUSTOMER_ADDR_LINE2, CUSTOMER_CITY, CUSTOMER_POSTCODE, CUSTOMER_COUNTRY, "
			+ "CUSTOMER_STATUS, CUSTOMER_CREATED_DATE, CUSTOMER_CREDIT_SCORE, CUSTOMER_CS_REVIEW_DATE";

	private final Connection connection;
	private final String table;

	/** Uses the unqualified table name, resolved by the connection's current schema. */
	public CustomerRepository(Connection connection) {
		this(connection, null);
	}

	public CustomerRepository(Connection connection, String schema) {
		this.connection = Objects.requireNonNull(connection, "connection");
		this.table = JdbcColumns.qualify(schema, "CUSTOMER");
	}

	/** INQCUST: read by key. */
	public Optional<Customer> findByKey(String sortcode, long customerNumber) throws SQLException {
		String sql = "SELECT " + COLUMNS + " FROM " + table
				+ " WHERE CUSTOMER_SORTCODE = ? AND CUSTOMER_NUMBER = ?";
		try (PreparedStatement ps = connection.prepareStatement(sql)) {
			ps.setString(1, requireSortcode(sortcode));
			ps.setString(2, formatNumber(customerNumber, 10, "CUSTOMER_NUMBER"));
			return queryOne(ps);
		}
	}

	/** INQCUST with customer number 9999999999: the highest-numbered customer for the sort code. */
	public Optional<Customer> findLast(String sortcode) throws SQLException {
		String sql = "SELECT " + COLUMNS + " FROM " + table
				+ " WHERE CUSTOMER_SORTCODE = ? ORDER BY CUSTOMER_NUMBER DESC FETCH FIRST 1 ROW ONLY";
		try (PreparedStatement ps = connection.prepareStatement(sql)) {
			ps.setString(1, requireSortcode(sortcode));
			return queryOne(ps);
		}
	}

	/** CRECUST: insert all columns. The customer number must already be allocated. */
	public void insert(Customer customer) throws SQLException {
		String sql = "INSERT INTO " + table + " (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
		try (PreparedStatement ps = connection.prepareStatement(sql)) {
			setChar(ps, 1, customer.getEyecatcher(), 4, "CUSTOMER_EYECATCHER");
			ps.setString(2, requireSortcode(customer.getSortcode()));
			ps.setString(3, formatNumber(customer.getNumber(), 10, "CUSTOMER_NUMBER"));
			setChar(ps, 4, customer.getTitle(), 10, "CUSTOMER_TITLE");
			setChar(ps, 5, customer.getFirstName(), 50, "CUSTOMER_FIRST_NAME");
			setChar(ps, 6, customer.getLastName(), 50, "CUSTOMER_LAST_NAME");
			setYyyymmdd(ps, 7, customer.getDateOfBirth());
			setChar(ps, 8, customer.getPhone(), 20, "CUSTOMER_PHONE");
			setChar(ps, 9, customer.getAddressLine1(), 50, "CUSTOMER_ADDR_LINE1");
			setChar(ps, 10, customer.getAddressLine2(), 50, "CUSTOMER_ADDR_LINE2");
			setChar(ps, 11, customer.getCity(), 50, "CUSTOMER_CITY");
			setChar(ps, 12, customer.getPostcode(), 10, "CUSTOMER_POSTCODE");
			setChar(ps, 13, customer.getCountry(), 50, "CUSTOMER_COUNTRY");
			setChar(ps, 14, customer.getStatus(), 10, "CUSTOMER_STATUS");
			setYyyymmdd(ps, 15, customer.getCreatedDate());
			setCreditScore(ps, 16, customer.getCreditScore());
			setYyyymmdd(ps, 17, customer.getCreditScoreReviewDate());
			ps.executeUpdate();
		}
	}

	/**
	 * UPDCUST: updates name, date of birth, phone, address and status. Eyecatcher, created date and credit
	 * score fields are left unchanged, as in the COBOL program.
	 *
	 * @return false if no customer has this key
	 */
	public boolean update(Customer customer) throws SQLException {
		String sql = "UPDATE " + table + " SET CUSTOMER_TITLE = ?, CUSTOMER_FIRST_NAME = ?, CUSTOMER_LAST_NAME = ?, "
				+ "CUSTOMER_DATE_OF_BIRTH = ?, CUSTOMER_PHONE = ?, CUSTOMER_ADDR_LINE1 = ?, CUSTOMER_ADDR_LINE2 = ?, "
				+ "CUSTOMER_CITY = ?, CUSTOMER_POSTCODE = ?, CUSTOMER_COUNTRY = ?, CUSTOMER_STATUS = ? "
				+ "WHERE CUSTOMER_SORTCODE = ? AND CUSTOMER_NUMBER = ?";
		try (PreparedStatement ps = connection.prepareStatement(sql)) {
			setChar(ps, 1, customer.getTitle(), 10, "CUSTOMER_TITLE");
			setChar(ps, 2, customer.getFirstName(), 50, "CUSTOMER_FIRST_NAME");
			setChar(ps, 3, customer.getLastName(), 50, "CUSTOMER_LAST_NAME");
			setYyyymmdd(ps, 4, customer.getDateOfBirth());
			setChar(ps, 5, customer.getPhone(), 20, "CUSTOMER_PHONE");
			setChar(ps, 6, customer.getAddressLine1(), 50, "CUSTOMER_ADDR_LINE1");
			setChar(ps, 7, customer.getAddressLine2(), 50, "CUSTOMER_ADDR_LINE2");
			setChar(ps, 8, customer.getCity(), 50, "CUSTOMER_CITY");
			setChar(ps, 9, customer.getPostcode(), 10, "CUSTOMER_POSTCODE");
			setChar(ps, 10, customer.getCountry(), 50, "CUSTOMER_COUNTRY");
			setChar(ps, 11, customer.getStatus(), 10, "CUSTOMER_STATUS");
			ps.setString(12, requireSortcode(customer.getSortcode()));
			ps.setString(13, formatNumber(customer.getNumber(), 10, "CUSTOMER_NUMBER"));
			return ps.executeUpdate() > 0;
		}
	}

	/**
	 * DELCUS: deletes the customer row only. DELCUS deletes the customer's accounts first; callers must do
	 * the same via {@link AccountRepository}.
	 *
	 * @return false if no customer has this key
	 */
	public boolean delete(String sortcode, long customerNumber) throws SQLException {
		String sql = "DELETE FROM " + table + " WHERE CUSTOMER_SORTCODE = ? AND CUSTOMER_NUMBER = ?";
		try (PreparedStatement ps = connection.prepareStatement(sql)) {
			ps.setString(1, requireSortcode(sortcode));
			ps.setString(2, formatNumber(customerNumber, 10, "CUSTOMER_NUMBER"));
			return ps.executeUpdate() > 0;
		}
	}

	private static void setCreditScore(PreparedStatement ps, int index, Integer score) throws SQLException {
		if (score != null && (score < 0 || score > 999)) {
			throw new IllegalArgumentException("CUSTOMER_CREDIT_SCORE must be 0-999 (PIC 999): " + score);
		}
		setInteger(ps, index, score, Types.SMALLINT);
	}

	private static Optional<Customer> queryOne(PreparedStatement ps) throws SQLException {
		try (ResultSet rs = ps.executeQuery()) {
			return rs.next() ? Optional.of(map(rs)) : Optional.empty();
		}
	}

	private static Customer map(ResultSet rs) throws SQLException {
		Customer c = new Customer();
		c.setEyecatcher(getChar(rs, "CUSTOMER_EYECATCHER"));
		c.setSortcode(getChar(rs, "CUSTOMER_SORTCODE"));
		c.setNumber(parseNumber(rs, "CUSTOMER_NUMBER"));
		c.setTitle(getChar(rs, "CUSTOMER_TITLE"));
		c.setFirstName(getChar(rs, "CUSTOMER_FIRST_NAME"));
		c.setLastName(getChar(rs, "CUSTOMER_LAST_NAME"));
		c.setDateOfBirth(getYyyymmdd(rs, "CUSTOMER_DATE_OF_BIRTH"));
		c.setPhone(getChar(rs, "CUSTOMER_PHONE"));
		c.setAddressLine1(getChar(rs, "CUSTOMER_ADDR_LINE1"));
		c.setAddressLine2(getChar(rs, "CUSTOMER_ADDR_LINE2"));
		c.setCity(getChar(rs, "CUSTOMER_CITY"));
		c.setPostcode(getChar(rs, "CUSTOMER_POSTCODE"));
		c.setCountry(getChar(rs, "CUSTOMER_COUNTRY"));
		c.setStatus(getChar(rs, "CUSTOMER_STATUS"));
		c.setCreatedDate(getYyyymmdd(rs, "CUSTOMER_CREATED_DATE"));
		c.setCreditScore(getInteger(rs, "CUSTOMER_CREDIT_SCORE"));
		c.setCreditScoreReviewDate(getYyyymmdd(rs, "CUSTOMER_CS_REVIEW_DATE"));
		return c;
	}
}
