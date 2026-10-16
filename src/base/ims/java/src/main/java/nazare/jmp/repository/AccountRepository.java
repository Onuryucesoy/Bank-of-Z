package nazare.jmp.repository;

import static nazare.jmp.repository.JdbcColumns.formatNumber;
import static nazare.jmp.repository.JdbcColumns.getChar;
import static nazare.jmp.repository.JdbcColumns.getDate;
import static nazare.jmp.repository.JdbcColumns.getInteger;
import static nazare.jmp.repository.JdbcColumns.parseNumber;
import static nazare.jmp.repository.JdbcColumns.requireSortcode;
import static nazare.jmp.repository.JdbcColumns.setChar;
import static nazare.jmp.repository.JdbcColumns.setDate;
import static nazare.jmp.repository.JdbcColumns.setDecimal;
import static nazare.jmp.repository.JdbcColumns.setInteger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import nazare.jmp.model.Account;

/**
 * JDBC access to the Db2 {@code ACCOUNT} table ({@code ACCDB2}), mirroring the SQL in INQACC, INQACCCU,
 * CREACC, UPDACC and DELACC. The caller owns the connection and its transaction.
 */
public class AccountRepository {

	private static final String COLUMNS = "ACCOUNT_EYECATCHER, ACCOUNT_CUSTOMER_NUMBER, ACCOUNT_SORTCODE, "
			+ "ACCOUNT_NUMBER, ACCOUNT_TYPE, ACCOUNT_INTEREST_RATE, ACCOUNT_OPENED, ACCOUNT_OVERDRAFT_LIMIT, "
			+ "ACCOUNT_LAST_STATEMENT, ACCOUNT_NEXT_STATEMENT, ACCOUNT_AVAILABLE_BALANCE, ACCOUNT_ACTUAL_BALANCE";

	// Precisions follow the deployed DDL (.setup/jcl/cics/Db2-create.j2), which is wider than ACCDB2.
	private static final int INTEREST_RATE_PRECISION = 6;
	private static final int BALANCE_PRECISION = 12;

	private final Connection connection;
	private final String table;

	/** Uses the unqualified table name, resolved by the connection's current schema. */
	public AccountRepository(Connection connection) {
		this(connection, null);
	}

	public AccountRepository(Connection connection, String schema) {
		this.connection = Objects.requireNonNull(connection, "connection");
		this.table = JdbcColumns.qualify(schema, "ACCOUNT");
	}

	/** INQACC: read by key. */
	public Optional<Account> findByKey(String sortcode, long accountNumber) throws SQLException {
		String sql = "SELECT " + COLUMNS + " FROM " + table + " WHERE ACCOUNT_SORTCODE = ? AND ACCOUNT_NUMBER = ?";
		try (PreparedStatement ps = connection.prepareStatement(sql)) {
			ps.setString(1, requireSortcode(sortcode));
			ps.setString(2, formatNumber(accountNumber, 8, "ACCOUNT_NUMBER"));
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? Optional.of(map(rs)) : Optional.empty();
			}
		}
	}

	/** INQACC with account number 99999999: the highest-numbered account for the sort code. */
	public Optional<Account> findLast(String sortcode) throws SQLException {
		String sql = "SELECT " + COLUMNS + " FROM " + table
				+ " WHERE ACCOUNT_SORTCODE = ? ORDER BY ACCOUNT_NUMBER DESC FETCH FIRST 1 ROW ONLY";
		try (PreparedStatement ps = connection.prepareStatement(sql)) {
			ps.setString(1, requireSortcode(sortcode));
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? Optional.of(map(rs)) : Optional.empty();
			}
		}
	}

	/** INQACCCU: all accounts for a customer, ordered by account number. */
	public List<Account> findByCustomer(String sortcode, long customerNumber) throws SQLException {
		String sql = "SELECT " + COLUMNS + " FROM " + table
				+ " WHERE ACCOUNT_CUSTOMER_NUMBER = ? AND ACCOUNT_SORTCODE = ? ORDER BY ACCOUNT_NUMBER";
		try (PreparedStatement ps = connection.prepareStatement(sql)) {
			ps.setString(1, formatNumber(customerNumber, 10, "ACCOUNT_CUSTOMER_NUMBER"));
			ps.setString(2, requireSortcode(sortcode));
			List<Account> accounts = new ArrayList<>();
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					accounts.add(map(rs));
				}
			}
			return accounts;
		}
	}

	/** CREACC: insert all columns. The account number must already be allocated. */
	public void insert(Account account) throws SQLException {
		String sql = "INSERT INTO " + table + " (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
		try (PreparedStatement ps = connection.prepareStatement(sql)) {
			setChar(ps, 1, account.getEyecatcher(), 4, "ACCOUNT_EYECATCHER");
			ps.setString(2, formatNumber(account.getCustomerNumber(), 10, "ACCOUNT_CUSTOMER_NUMBER"));
			ps.setString(3, requireSortcode(account.getSortcode()));
			ps.setString(4, formatNumber(account.getNumber(), 8, "ACCOUNT_NUMBER"));
			setChar(ps, 5, account.getType(), 8, "ACCOUNT_TYPE");
			setDecimal(ps, 6, account.getInterestRate(), INTEREST_RATE_PRECISION, "ACCOUNT_INTEREST_RATE");
			setDate(ps, 7, account.getOpened());
			setInteger(ps, 8, account.getOverdraftLimit(), Types.INTEGER);
			setDate(ps, 9, account.getLastStatement());
			setDate(ps, 10, account.getNextStatement());
			setDecimal(ps, 11, account.getAvailableBalance(), BALANCE_PRECISION, "ACCOUNT_AVAILABLE_BALANCE");
			setDecimal(ps, 12, account.getActualBalance(), BALANCE_PRECISION, "ACCOUNT_ACTUAL_BALANCE");
			ps.executeUpdate();
		}
	}

	/**
	 * UPDACC: updates type, interest rate and overdraft limit only. Balances and dates are left unchanged,
	 * as in the COBOL program.
	 *
	 * @return false if no account has this key
	 */
	public boolean update(Account account) throws SQLException {
		String sql = "UPDATE " + table + " SET ACCOUNT_TYPE = ?, ACCOUNT_INTEREST_RATE = ?, ACCOUNT_OVERDRAFT_LIMIT = ? "
				+ "WHERE ACCOUNT_SORTCODE = ? AND ACCOUNT_NUMBER = ?";
		try (PreparedStatement ps = connection.prepareStatement(sql)) {
			setChar(ps, 1, account.getType(), 8, "ACCOUNT_TYPE");
			setDecimal(ps, 2, account.getInterestRate(), INTEREST_RATE_PRECISION, "ACCOUNT_INTEREST_RATE");
			setInteger(ps, 3, account.getOverdraftLimit(), Types.INTEGER);
			ps.setString(4, requireSortcode(account.getSortcode()));
			ps.setString(5, formatNumber(account.getNumber(), 8, "ACCOUNT_NUMBER"));
			return ps.executeUpdate() > 0;
		}
	}

	/**
	 * DELACC: deletes the account row.
	 *
	 * @return false if no account has this key
	 */
	public boolean delete(String sortcode, long accountNumber) throws SQLException {
		String sql = "DELETE FROM " + table + " WHERE ACCOUNT_SORTCODE = ? AND ACCOUNT_NUMBER = ?";
		try (PreparedStatement ps = connection.prepareStatement(sql)) {
			ps.setString(1, requireSortcode(sortcode));
			ps.setString(2, formatNumber(accountNumber, 8, "ACCOUNT_NUMBER"));
			return ps.executeUpdate() > 0;
		}
	}

	private static Account map(ResultSet rs) throws SQLException {
		Account a = new Account();
		a.setEyecatcher(getChar(rs, "ACCOUNT_EYECATCHER"));
		a.setCustomerNumber(parseNumber(rs, "ACCOUNT_CUSTOMER_NUMBER"));
		a.setSortcode(getChar(rs, "ACCOUNT_SORTCODE"));
		a.setNumber(parseNumber(rs, "ACCOUNT_NUMBER"));
		a.setType(getChar(rs, "ACCOUNT_TYPE"));
		a.setInterestRate(rs.getBigDecimal("ACCOUNT_INTEREST_RATE"));
		a.setOpened(getDate(rs, "ACCOUNT_OPENED"));
		a.setOverdraftLimit(getInteger(rs, "ACCOUNT_OVERDRAFT_LIMIT"));
		a.setLastStatement(getDate(rs, "ACCOUNT_LAST_STATEMENT"));
		a.setNextStatement(getDate(rs, "ACCOUNT_NEXT_STATEMENT"));
		a.setAvailableBalance(rs.getBigDecimal("ACCOUNT_AVAILABLE_BALANCE"));
		a.setActualBalance(rs.getBigDecimal("ACCOUNT_ACTUAL_BALANCE"));
		return a;
	}
}
