package com.ibm.bankofz.core.codec;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.util.Arrays;

/**
 * PIC X (alphanumeric DISPLAY) codec. The CICS z/OS Connect services are
 * defined with {@code ccsid: 037}; this is the only place text crosses the
 * EBCDIC boundary. Encoding follows COBOL MOVE semantics: left-justified,
 * space padded, truncated on the right.
 */
public final class Ebcdic {

    public static final Charset CP037 = Charset.forName("IBM037");
    public static final byte SPACE = 0x40;

    private final Charset charset;

    public Ebcdic(Charset charset) {
        this.charset = charset;
    }

    public static Ebcdic cp037() {
        return new Ebcdic(CP037);
    }

    public Charset charset() {
        return charset;
    }

    public byte spaceByte() {
        return charset.encode(" ").get(0);
    }

    /** Encodes {@code value} into exactly {@code length} bytes (MOVE semantics). */
    public byte[] encode(String value, int length) {
        byte[] out = new byte[length];
        Arrays.fill(out, spaceByte());
        if (value == null || value.isEmpty()) {
            return out;
        }
        String clipped = value.length() > length ? value.substring(0, length) : value;
        byte[] raw = toBytes(clipped);
        System.arraycopy(raw, 0, out, 0, Math.min(raw.length, length));
        return out;
    }

    /** Strict variant used by fixtures: refuses values that COBOL would silently truncate. */
    public byte[] encodeExact(String value, int length) {
        if (value != null && value.length() > length) {
            throw new CodecException("value of length " + value.length() + " exceeds PIC X(" + length + ")");
        }
        return encode(value, length);
    }

    /** Decodes bytes verbatim (trailing spaces are preserved, as in the COBOL field). */
    public String decode(byte[] data, int offset, int length) {
        try {
            return newDecoder().decode(ByteBuffer.wrap(data, offset, length)).toString();
        } catch (CharacterCodingException e) {
            throw new CodecException("bytes are not valid " + charset.name(), e);
        }
    }

    public static String rtrim(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == ' ') {
            end--;
        }
        return s.substring(0, end);
    }

    private byte[] toBytes(String text) {
        CharsetEncoder encoder = charset.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            ByteBuffer buf = encoder.encode(CharBuffer.wrap(text));
            byte[] b = new byte[buf.remaining()];
            buf.get(b);
            return b;
        } catch (CharacterCodingException e) {
            throw new CodecException("text not representable in " + charset.name(), e);
        }
    }

    private CharsetDecoder newDecoder() {
        return charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
    }
}
