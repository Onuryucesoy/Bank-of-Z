package nazare.jmp.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import nazare.jmp.model.Account;
import nazare.jmp.model.SortCode;

class AccountRepositoryTest {

	private Connection connection;
	private AccountRepository repository;

	@BeforeEach
	void setUp() throws SQLException {
		connection = H2Db2.open();
		repository = new AccountRepository(connection, "BANKZ");
	}

	@AfterEach
	void tearDown() throws SQLException {
		connection.close();
	}

	@Test
	void insertThenFindByKeyRoundTripsAllFields() throws SQLException {
		Account account = account(12345678, 42);
		repository.insert(account);

		assertEquals(account, repository.findByKey(SortCode.VALUE, 12345678).orElseThrow());
	}

	@Test
	void storesKeysZeroPadded() throws SQLException {
		repository.insert(account(7, 42));

		try (Statement st = connection.createStatement();
				ResultSet rs = st.executeQuery("SELECT ACCOUNT_NUMBER, ACCOUNT_CUSTOMER_NUMBER FROM BANKZ.ACCOUNT")) {
			assertTrue(rs.next());
			assertEquals("00000007", rs.getString(1));
			assertEquals("0000000042", rs.getString(2));
		}
	}

	@Test
	void readsNullableColumnsAsNull() throws SQLException {
		try (Statement st = connection.createStatement()) {
			st.executeUpdate("INSERT INTO BANKZ.ACCOUNT (ACCOUNT_SORTCODE, ACCOUNT_NUMBER) VALUES ('987654', '00000009')");
		}

		Account found = repository.findByKey(SortCode.VALUE, 9).orElseThrow();

		assertNull(found.getType());
		assertNull(found.getOpened());
		assertNull(found.getOverdraftLimit());
		assertNull(found.getActualBalance());
		assertEquals(0, found.getCustomerNumber());
	}

	@Test
	void findByCustomerReturnsOnlyThatCustomersAccountsInOrder() throws SQLException {
		repository.insert(account(30, 42));
		repository.insert(account(10, 42));
		repository.insert(account(20, 43));
		Account otherBank = account(40, 42);
		otherBank.setSortcode("111111");
		repository.insert(otherBank);

		List<Account> accounts = repository.findByCustomer(SortCode.VALUE, 42);

		assertEquals(List.of(10L, 30L), accounts.stream().map(Account::getNumber).toList());
		assertTrue(repository.findByCustomer(SortCode.VALUE, 99).isEmpty());
	}

	@Test
	void findLastReturnsHighestAccountNumberForSortcode() throws SQLException {
		repository.insert(account(10, 42));
		repository.insert(account(99, 43));
		Account otherBank = account(500, 42);
		otherBank.setSortcode("111111");
		repository.insert(otherBank);

		assertEquals(99, repository.findLast(SortCode.VALUE).orElseThrow().getNumber());
		assertTrue(repository.findLast("222222").isEmpty());
	}

	@Test
	void updateChangesOnlyTypeRateAndOverdraft() throws SQLException {
		Account original = account(10, 42);
		repository.insert(original);

		Account change = account(10, 42);
		change.setType("SAVING");
		change.setInterestRate(new BigDecimal("2.50"));
		change.setOverdraftLimit(0);
		change.setActualBalance(new BigDecimal("1.00"));
		change.setAvailableBalance(new BigDecimal("1.00"));
		change.setOpened(LocalDate.of(2000, 1, 1));
		assertTrue(repository.update(change));

		Account found = repository.findByKey(SortCode.VALUE, 10).orElseThrow();
		assertEquals("SAVING", found.getType());
		assertEquals(new BigDecimal("2.50"), found.getInterestRate());
		assertEquals(0, found.getOverdraftLimit());
		assertEquals(original.getActualBalance(), found.getActualBalance());
		assertEquals(original.getAvailableBalance(), found.getAvailableBalance());
		assertEquals(original.getOpened(), found.getOpened());
	}

	@Test
	void updateAndDeleteReturnFalseWhenMissing() throws SQLException {
		assertFalse(repository.update(account(10, 42)));
		assertFalse(repository.delete(SortCode.VALUE, 10));
	}

	@Test
	void deleteRemovesAccount() throws SQLException {
		repository.insert(account(10, 42));

		assertTrue(repository.delete(SortCode.VALUE, 10));
		assertTrue(repository.findByKey(SortCode.VALUE, 10).isEmpty());
	}

	@Test
	void normalisesAmountsToScaleTwo() throws SQLException {
		Account account = account(10, 42);
		account.setActualBalance(new BigDecimal("5"));
		repository.insert(account);

		assertEquals(new BigDecimal("5.00"), repository.findByKey(SortCode.VALUE, 10).orElseThrow().getActualBalance());
	}

	@Test
	void acceptsLargestBalanceTheDdlAllows() throws SQLException {
		Account account = account(10, 42);
		account.setActualBalance(new BigDecimal("-9999999999.99"));
		repository.insert(account);

		assertEquals(new BigDecimal("-9999999999.99"),
				repository.findByKey(SortCode.VALUE, 10).orElseThrow().getActualBalance());
	}

	@Test
	void rejectsValuesDb2WouldTruncateOrOverflow() {
		Account fractionalPenny = account(10, 42);
		fractionalPenny.setActualBalance(new BigDecimal("1.005"));
		assertThrows(IllegalArgumentException.class, () -> repository.insert(fractionalPenny));

		Account tooLarge = account(10, 42);
		tooLarge.setAvailableBalance(new BigDecimal("10000000000.00"));
		assertThrows(IllegalArgumentException.class, () -> repository.insert(tooLarge));

		Account longType = account(10, 42);
		longType.setType("CURRENTXX");
		assertThrows(IllegalArgumentException.class, () -> repository.insert(longType));

		assertThrows(IllegalArgumentException.class, () -> repository.insert(account(Account.MAX_NUMBER + 1, 42)));
		assertThrows(IllegalArgumentException.class, () -> repository.findByKey(null, 1));
	}

	static Account account(long number, long customerNumber) {
		Account a = new Account(SortCode.VALUE, number);
		a.setCustomerNumber(customerNumber);
		a.setType("CURRENT");
		a.setInterestRate(new BigDecimal("1.75"));
		a.setOpened(LocalDate.of(2020, 6, 1));
		a.setOverdraftLimit(500);
		a.setLastStatement(LocalDate.of(2024, 1, 1));
		a.setNextStatement(LocalDate.of(2024, 2, 1));
		a.setAvailableBalance(new BigDecimal("1234.56"));
		a.setActualBalance(new BigDecimal("1200.00"));
		return a;
	}
}
