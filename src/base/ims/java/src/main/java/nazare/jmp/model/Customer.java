package nazare.jmp.model;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Customer record, from copybooks {@code CUSTDB2} (Db2 table {@code CUSTOMER}) and {@code CUSTOMER}
 * ({@code CUSTOMER-RECORD}).
 *
 * <p>Key: {@code CUSTOMER_SORTCODE} + {@code CUSTOMER_NUMBER}. The three dates are stored in Db2 as
 * {@code INTEGER} {@code YYYYMMDD} and exposed here as {@link LocalDate}.
 */
public class Customer {

	public static final String EYECATCHER = "CUST";

	public static final String STATUS_ACTIVE = "ACTIVE";
	public static final String STATUS_INACTIVE = "INACTIVE";
	public static final String STATUS_SUSPENDED = "SUSPENDED";

	public static final long MAX_NUMBER = 9_999_999_999L;

	private String eyecatcher = EYECATCHER;    // CHAR(4)     PIC X(4)
	private String sortcode = SortCode.VALUE;  // CHAR(6)     PIC 9(6)
	private long number;                       // CHAR(10)    PIC 9(10)
	private String title;                      // CHAR(10)    PIC X(10)
	private String firstName;                  // CHAR(50)    PIC X(50)
	private String lastName;                   // CHAR(50)    PIC X(50)
	private LocalDate dateOfBirth;             // INTEGER     DD/MM/YYYY group
	private String phone;                      // CHAR(20)    PIC X(20)
	private String addressLine1;               // CHAR(50)    PIC X(50)
	private String addressLine2;               // CHAR(50)    PIC X(50)
	private String city;                       // CHAR(50)    PIC X(50)
	private String postcode;                   // CHAR(10)    PIC X(10)
	private String country;                    // CHAR(50)    PIC X(50)
	private String status;                     // CHAR(10)    PIC X(10)
	private LocalDate createdDate;             // INTEGER     DD/MM/YYYY group
	private Integer creditScore;               // SMALLINT    PIC 999
	private LocalDate creditScoreReviewDate;   // INTEGER     DD/MM/YYYY group

	public Customer() {
	}

	public Customer(String sortcode, long number) {
		this.sortcode = sortcode;
		this.number = number;
	}

	public boolean isActive() {
		return STATUS_ACTIVE.equals(status);
	}

	public String getEyecatcher() {
		return eyecatcher;
	}

	public void setEyecatcher(String eyecatcher) {
		this.eyecatcher = eyecatcher;
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

	public String getTitle() {
		return title;
	}

	public void setTitle(String title) {
		this.title = title;
	}

	public String getFirstName() {
		return firstName;
	}

	public void setFirstName(String firstName) {
		this.firstName = firstName;
	}

	public String getLastName() {
		return lastName;
	}

	public void setLastName(String lastName) {
		this.lastName = lastName;
	}

	public LocalDate getDateOfBirth() {
		return dateOfBirth;
	}

	public void setDateOfBirth(LocalDate dateOfBirth) {
		this.dateOfBirth = dateOfBirth;
	}

	public String getPhone() {
		return phone;
	}

	public void setPhone(String phone) {
		this.phone = phone;
	}

	public String getAddressLine1() {
		return addressLine1;
	}

	public void setAddressLine1(String addressLine1) {
		this.addressLine1 = addressLine1;
	}

	public String getAddressLine2() {
		return addressLine2;
	}

	public void setAddressLine2(String addressLine2) {
		this.addressLine2 = addressLine2;
	}

	public String getCity() {
		return city;
	}

	public void setCity(String city) {
		this.city = city;
	}

	public String getPostcode() {
		return postcode;
	}

	public void setPostcode(String postcode) {
		this.postcode = postcode;
	}

	public String getCountry() {
		return country;
	}

	public void setCountry(String country) {
		this.country = country;
	}

	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	public LocalDate getCreatedDate() {
		return createdDate;
	}

	public void setCreatedDate(LocalDate createdDate) {
		this.createdDate = createdDate;
	}

	public Integer getCreditScore() {
		return creditScore;
	}

	public void setCreditScore(Integer creditScore) {
		this.creditScore = creditScore;
	}

	public LocalDate getCreditScoreReviewDate() {
		return creditScoreReviewDate;
	}

	public void setCreditScoreReviewDate(LocalDate creditScoreReviewDate) {
		this.creditScoreReviewDate = creditScoreReviewDate;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof Customer)) {
			return false;
		}
		Customer c = (Customer) o;
		return number == c.number && Objects.equals(eyecatcher, c.eyecatcher) && Objects.equals(sortcode, c.sortcode)
				&& Objects.equals(title, c.title) && Objects.equals(firstName, c.firstName)
				&& Objects.equals(lastName, c.lastName) && Objects.equals(dateOfBirth, c.dateOfBirth)
				&& Objects.equals(phone, c.phone) && Objects.equals(addressLine1, c.addressLine1)
				&& Objects.equals(addressLine2, c.addressLine2) && Objects.equals(city, c.city)
				&& Objects.equals(postcode, c.postcode) && Objects.equals(country, c.country)
				&& Objects.equals(status, c.status) && Objects.equals(createdDate, c.createdDate)
				&& Objects.equals(creditScore, c.creditScore)
				&& Objects.equals(creditScoreReviewDate, c.creditScoreReviewDate);
	}

	@Override
	public int hashCode() {
		return Objects.hash(sortcode, number);
	}

	@Override
	public String toString() {
		return "Customer[" + sortcode + "/" + number + " " + title + " " + firstName + " " + lastName + ", status="
				+ status + "]";
	}
}
