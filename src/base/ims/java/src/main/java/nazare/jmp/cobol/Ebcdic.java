package nazare.jmp.cobol;

import java.nio.charset.Charset;
import java.util.Arrays;

/** EBCDIC code page 1047 helpers for {@code PIC X(n)} fields, as used by the IMS DBDs ({@code ENCODING=Cp1047}). */
public final class Ebcdic {

    public static final Charset CP1047 = Charset.forName("IBM1047");
    public static final byte SPACE = 0x40;
    public static final byte LOW_VALUE = 0x00;
    public static final byte HIGH_VALUE = (byte) 0xFF;

    private Ebcdic() {
    }

    /**
     * Writes {@code text} into a {@code PIC X(length)} field, right-padded with EBCDIC spaces.
     * Throws if the text is longer than the field (COBOL would truncate on the right).
     */
    public static void writeAlphanumeric(String text, byte[] target, int offset, int length) {
        byte[] encoded = text.getBytes(CP1047);
        if (encoded.length > length) {
            throw new IllegalArgumentException("Value of " + encoded.length + " bytes does not fit PIC X(" + length + ")");
        }
        System.arraycopy(encoded, 0, target, offset, encoded.length);
        Arrays.fill(target, offset + encoded.length, offset + length, SPACE);
    }

    /** Reads a {@code PIC X(length)} field, dropping trailing EBCDIC spaces only. */
    public static String readAlphanumeric(byte[] source, int offset, int length) {
        int end = offset + length;
        while (end > offset && source[end - 1] == SPACE) {
            end--;
        }
        return new String(source, offset, end - offset, CP1047);
    }

    /** True if every byte of the field is EBCDIC space or every byte is LOW-VALUE (the INQACC not-found test). */
    public static boolean isSpacesOrLowValues(byte[] source, int offset, int length) {
        return allEqual(source, offset, length, SPACE) || allEqual(source, offset, length, LOW_VALUE);
    }

    private static boolean allEqual(byte[] source, int offset, int length, byte b) {
        for (int i = offset; i < offset + length; i++) {
            if (source[i] != b) {
                return false;
            }
        }
        return true;
    }
}
