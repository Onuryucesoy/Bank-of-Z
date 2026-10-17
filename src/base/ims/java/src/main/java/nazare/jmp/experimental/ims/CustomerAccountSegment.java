package nazare.jmp.experimental.ims;

/**
 * CUSTACCS segment (external name CUSTOMERACCS, 16 bytes): links a customer to an account.
 * CUSTID is a non-unique sequence field ({@code SEQ,M}); ACCNUM is the account's ordinal for that customer.
 */
public record CustomerAccountSegment(int custId, long accId, int accNum) {
}
