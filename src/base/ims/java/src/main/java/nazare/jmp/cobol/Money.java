package nazare.jmp.cobol;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Objects;

/**
 * Monetary amount with the semantics of {@code PIC S9(n)V99} (scale 2), backed by {@link BigDecimal}.
 *
 * <ul>
 *   <li>The value always has scale 2; {@code equals} is numeric ("1.5" equals "1.50").</li>
 *   <li>Factories never round silently: {@link #of} rejects more than 2 decimals; use
 *       {@link #of(BigDecimal, CobolRounding)} to choose COBOL truncation or ROUNDED.</li>
 *   <li>{@link #plus}/{@link #minus}/{@link #negate} are exact. {@link #times} applies the given rounding
 *       to the exact product, like {@code COMPUTE X [ROUNDED] = A * B}.</li>
 *   <li>A Money is not tied to a PICTURE. Check it against one with {@link #fits}, {@link #requireFits}
 *       (an ON SIZE ERROR analogue) or {@link #truncateHighOrder} (what COBOL does without ON SIZE ERROR).</li>
 * </ul>
 */
public final class Money implements Comparable<Money> {

    public static final int SCALE = 2;
    public static final Money ZERO = new Money(BigDecimal.ZERO.setScale(SCALE));

    private final BigDecimal value;

    private Money(BigDecimal value) {
        this.value = value;
    }

    /** Exact conversion; throws {@link ArithmeticException} if {@code amount} has more than 2 significant decimals. */
    public static Money of(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount");
        return new Money(amount.setScale(SCALE, java.math.RoundingMode.UNNECESSARY));
    }

    public static Money of(String amount) {
        return of(new BigDecimal(amount));
    }

    /** Converts with the given COBOL rounding behaviour when {@code amount} has more than 2 decimals. */
    public static Money of(BigDecimal amount, CobolRounding rounding) {
        Objects.requireNonNull(amount, "amount");
        return new Money(amount.setScale(SCALE, rounding.roundingMode()));
    }

    public static Money ofCents(long cents) {
        return new Money(BigDecimal.valueOf(cents, SCALE));
    }

    public Money plus(Money other) {
        return new Money(value.add(other.value));
    }

    public Money minus(Money other) {
        return new Money(value.subtract(other.value));
    }

    public Money negate() {
        return new Money(value.negate());
    }

    public Money times(BigDecimal factor, CobolRounding rounding) {
        return of(value.multiply(factor), rounding);
    }

    public boolean fits(PicSpec pic) {
        requireScale2(pic);
        return pic.accepts(value);
    }

    /** Returns this amount, or throws {@link ArithmeticException} if it does not fit {@code pic}. */
    public Money requireFits(PicSpec pic) {
        if (!fits(pic)) {
            throw new ArithmeticException("Size error: " + toPlainString() + " does not fit PIC " + pic);
        }
        return this;
    }

    /**
     * COBOL store without ON SIZE ERROR: high-order integer digits that do not fit are dropped, and
     * an unsigned receiver keeps the absolute value. For example 12345678901.23 into S9(10)V99 becomes
     * 2345678901.23.
     */
    public Money truncateHighOrder(PicSpec pic) {
        requireScale2(pic);
        BigInteger unscaled = value.unscaledValue();
        BigInteger modulus = BigInteger.TEN.pow(pic.totalDigits());
        BigInteger kept = unscaled.abs().mod(modulus);
        if (pic.signed() && unscaled.signum() < 0) {
            kept = kept.negate();
        }
        return new Money(new BigDecimal(kept, SCALE));
    }

    public BigDecimal toBigDecimal() {
        return value;
    }

    /** Amount in cents; throws {@link ArithmeticException} if it overflows a long. */
    public long toCents() {
        return value.unscaledValue().longValueExact();
    }

    public int signum() {
        return value.signum();
    }

    public boolean isNegative() {
        return value.signum() < 0;
    }

    public String toPlainString() {
        return value.toPlainString();
    }

    @Override
    public int compareTo(Money other) {
        return value.compareTo(other.value);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Money m && value.equals(m.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return toPlainString();
    }

    private static void requireScale2(PicSpec pic) {
        if (pic.scale() != SCALE) {
            throw new IllegalArgumentException("Money has scale 2; PIC " + pic + " does not");
        }
    }
}
