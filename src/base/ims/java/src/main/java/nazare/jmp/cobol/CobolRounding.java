package nazare.jmp.cobol;

import java.math.RoundingMode;

/**
 * How a result with too many decimals is fitted to the receiving PICTURE.
 *
 * <p>No program in this repository uses {@code ROUNDED}, so {@link #TRUNCATE} is the behaviour
 * every migrated program must reproduce by default.
 */
public enum CobolRounding {
    /** COBOL default for MOVE / COMPUTE without ROUNDED: excess decimals are dropped (toward zero). */
    TRUNCATE(RoundingMode.DOWN),
    /** COBOL {@code ROUNDED} (default mode NEAREST-AWAY-FROM-ZERO): half away from zero. */
    ROUNDED(RoundingMode.HALF_UP);

    private final RoundingMode mode;

    CobolRounding(RoundingMode mode) {
        this.mode = mode;
    }

    public RoundingMode roundingMode() {
        return mode;
    }
}
