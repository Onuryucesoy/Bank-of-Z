package nazare.jmp.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Account record, from copybook {@code ACCDB2} (Db2 table {@code ACCOUNT}).
 *
 * <p>Key: {@code ACCOUNT_SORTCODE} + {@code ACCOUNT_NUMBER}. Monetary amounts and the interest rate
 * have scale 2.
 */
public class Account {

	public static final String EYECATCHER = "ACCT";

	public static final long MAX_NUMBER = 99_999_999L;

	private String eyecatcher = EYECATCHER;    // CHAR(4)
	private long customerNumber;               // CHAR(10)
	private String sortcode = SortCode.VALUE;  // CHAR(6) NOT NULL
	private long number;                       // CHAR(8) NOT NULL
	private String type;                       // CHAR(8)
	private BigDecimal interestRate;           // DECIMAL(4,2) in ACCDB2, DECIMAL(6,2) in DDL
	private LocalDate opened;                  // DATE
	private Integer overdraftLimit;            // INTEGER
	private LocalDate lastStatement;           // DATE
	private LocalDate nextStatement;           // DATE
	private BigDecimal availableBalance;       // DECIMAL(10,2) in ACCDB2, DECIMAL(12,2) in DDL
	private BigDecimal actualBalance;          // DECIMAL(10,2) in ACCDB2, DECIMAL(12,2) in DDL

	public Account() {
	}

	public Account(String sortcode, long number) {
		this.sortcode = sortcode;
		this.number = number;
	}

	public String getEyecatcher() {
		return eyecatcher;
	}

	public void setEyecatcher(String eyecatcher) {
		this.eyecatcher = eyecatcher;
	}

	public long getCustomerNumber() {
		return customerNumber;
	}

	public void setCustomerNumber(long customerNumber) {
		this.customerNumber = customerNumber;
	}

	public String getSortcode() {
		return sortcode;
	}

	public void setSortcode(String sortcode) {
		this.sortcode = sortcode;
	}

	public long getNumber() {
		return number;
	}

	public void setNumber(long number) {
		this.number = number;
	}

	public String getType() {
		return type;
	}

	public void setType(String type) {
		this.type = type;
	}

	public BigDecimal getInterestRate() {
		return interestRate;
	}

	public void setInterestRate(BigDecimal interestRate) {
		this.interestRate = interestRate;
	}

	public LocalDate getOpened() {
		return opened;
	}

	public void setOpened(LocalDate opened) {
		this.opened = opened;
	}

	public Integer getOverdraftLimit() {
		return overdraftLimit;
	}

	public void setOverdraftLimit(Integer overdraftLimit) {
		this.overdraftLimit = overdraftLimit;
	}

	public LocalDate getLastStatement() {
		return lastStatement;
	}

	public void setLastStatement(LocalDate lastStatement) {
		this.lastStatement = lastStatement;
	}

	public LocalDate getNextStatement() {
		return nextStatement;
	}

	public void setNextStatement(LocalDate nextStatement) {
		this.nextStatement = nextStatement;
	}

	public BigDecimal getAvailableBalance() {
		return availableBalance;
	}

	public void setAvailableBalance(BigDecimal availableBalance) {
		this.availableBalance = availableBalance;
	}

	public BigDecimal getActualBalance() {
		return actualBalance;
	}

	public void setActualBalance(BigDecimal actualBalance) {
		this.actualBalance = actualBalance;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof Account)) {
			return false;
		}
		Account a = (Account) o;
		return customerNumber == a.customerNumber && number == a.number && Objects.equals(eyecatcher, a.eyecatcher)
				&& Objects.equals(sortcode, a.sortcode) && Objects.equals(type, a.type)
				&& Objects.equals(interestRate, a.interestRate) && Objects.equals(opened, a.opened)
				&& Objects.equals(overdraftLimit, a.overdraftLimit) && Objects.equals(lastStatement, a.lastStatement)
				&& Objects.equals(nextStatement, a.nextStatement)
				&& Objects.equals(availableBalance, a.availableBalance)
				&& Objects.equals(actualBalance, a.actualBalance);
	}

	@Override
	public int hashCode() {
		return Objects.hash(sortcode, number);
	}

	@Override
	public String toString() {
		return "Account[" + sortcode + "/" + number + " customer=" + customerNumber + " type=" + type
				+ " actualBalance=" + actualBalance + "]";
	}
}
