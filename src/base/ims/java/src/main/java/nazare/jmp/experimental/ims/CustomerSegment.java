package nazare.jmp.experimental.ims;

/**
 * CUSTOMER segment (DBD {@code src/base/ims/DBD/CUSTOMER.asm}, 279 bytes, key CUSTID).
 * Text fields are as stored (space padded). {@link #toString()} redacts the password.
 */
public record CustomerSegment(int custId, String lastName, String firstName, String address, String city,
                              String state, String zipCode, String phone, String status, String password,
                              String customerType, String lastLogin) {

    /** {@code STATUS} value written by IBLOGIN on a successful login. */
    public static final String LOGGED_IN = "1";

    @Override
    public String toString() {
        return "CustomerSegment[custId=" + custId + ", lastName=" + lastName.strip() + ", firstName="
                + firstName.strip() + ", status=" + status + ", customerType=" + customerType
                + ", lastLogin=" + lastLogin + ", password=[REDACTED]]";
    }
}
