package nazare.jmp.experimental.ims;

import nazare.jmp.cobol.Money;

/** ACCOUNT segment (25 bytes, key ACCID). BALANCE is {@code S9(13)V9(2) COMP-3} / DECIMAL(15,2). */
public record AccountSegment(long accId, String accType, Money balance, long lastTxId) {
}
