package com.ibm.bankofz.migration.codec;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Byte-exact conversion between COBOL storage formats and Java values. Numeric items always map to
 * {@link BigDecimal} (never double/float); text uses an explicit EBCDIC charset (default IBM037, the CCSID 037
 * declared in the CICS {@code zosAsset.yaml} files). Encoding never rounds or truncates: values that do not fit
 * the PIC clause raise {@link CodecException}.
 */
public final class CopybookCodec {

    public static final Charset IBM037 = Charset.forName("IBM037");
    private static final byte EBCDIC_SPACE = 0x40;

    private final Charset charset;

    public CopybookCodec() {
        this(IBM037);
    }

    public CopybookCodec(Charset charset) {
        this.charset = charset;
    }

    public Charset charset() {
        return charset;
    }

    public static int packedSize(int digits) {
        return digits / 2 + 1;
    }

    public static int binarySize(int digits) {
        return digits <= 4 ? 2 : digits <= 9 ? 4 : 8;
    }

    // ------------------------------------------------------------------ PIC X

    /** Decodes PIC X(len) keeping the full fixed length (trailing spaces are significant for golden-master diffs). */
    public String decodeAlphanumeric(byte[] record, int offset, int length) {
        check(record, offset, length);
        try {
            return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(record, offset, length)).toString();
        } catch (CharacterCodingException e) {
            throw new CodecException("bytes at offset " + offset + " are not valid " + charset, e);
        }
    }

    /** Encodes into PIC X(len), right-padding with EBCDIC spaces; rejects values that are too long or unmappable. */
    public void encodeAlphanumeric(String value, byte[] record, int offset, int length) {
        check(record, offset, length);
        ByteBuffer bytes;
        try {
            bytes = charset.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value));
        } catch (CharacterCodingException e) {
            throw new CodecException("'" + value + "' has characters not representable in " + charset, e);
        }
        if (bytes.remaining() > length) {
            throw new CodecException("'" + value + "' is " + bytes.remaining() + " bytes, field is PIC X(" + length + ")");
        }
        int n = bytes.remaining();
        bytes.get(record, offset, n);
        Arrays.fill(record, offset + n, offset + length, spaceByte());
    }

    private byte spaceByte() {
        return charset.equals(IBM037) ? EBCDIC_SPACE : " ".getBytes(charset)[0];
    }

    // ------------------------------------------------------------------ PIC 9 DISPLAY (zoned)

    /** Zoned decimal: one digit per byte (zone F), sign overpunched in the zone of the last byte (C/F = +, D = -). */
    public BigDecimal decodeZoned(byte[] record, int offset, int digits, int scale, boolean signed) {
        check(record, offset, digits);
        StringBuilder sb = new StringBuilder(digits + 1);
        boolean negative = false;
        for (int i = 0; i < digits; i++) {
            int b = record[offset + i] & 0xFF;
            int zone = b >>> 4;
            int digit = b & 0x0F;
            if (digit > 9) {
                throw new CodecException(String.format("invalid zoned digit 0x%02X at offset %d", b, offset + i));
            }
            boolean last = i == digits - 1;
            if (last && signed && (zone == 0xD || zone == 0xB)) {
                negative = true;
            } else if (!(zone == 0xF || (last && signed && (zone == 0xC || zone == 0xA || zone == 0xE)))) {
                throw new CodecException(String.format("invalid zoned zone 0x%02X at offset %d", b, offset + i));
            }
            sb.append((char) ('0' + digit));
        }
        BigDecimal v = new BigDecimal(new BigInteger(sb.toString()), scale);
        return negative ? v.negate() : v;
    }

    public void encodeZoned(BigDecimal value, byte[] record, int offset, int digits, int scale, boolean signed) {
        check(record, offset, digits);
        String unscaled = unscaledDigits(value, digits, scale, signed);
        for (int i = 0; i < digits; i++) {
            record[offset + i] = (byte) (0xF0 | (unscaled.charAt(i) - '0'));
        }
        if (signed) {
            int lastIdx = offset + digits - 1;
            int sign = value.signum() < 0 ? 0xD0 : 0xC0;
            record[lastIdx] = (byte) (sign | (record[lastIdx] & 0x0F));
        }
    }

    /** PIC 9(n) date/time fields: returns the n raw digits (e.g. "15061985"), deliberately not a java.time type. */
    public String decodeRawDigits(byte[] record, int offset, int digits) {
        return decodeZoned(record, offset, digits, 0, false).toBigIntegerExact().toString().transform(s -> pad(s, digits));
    }

    public void encodeRawDigits(String value, byte[] record, int offset, int digits) {
        if (value == null || !value.matches("\\d{" + digits + "}")) {
            throw new CodecException("'" + value + "' is not exactly " + digits + " digits");
        }
        encodeZoned(new BigDecimal(value), record, offset, digits, 0, false);
    }

    // ------------------------------------------------------------------ COMP-3

    /** Packed decimal: two digits per byte, sign in the low nibble of the last byte (C/F/A/E = +, D/B = -). */
    public BigDecimal decodePacked(byte[] record, int offset, int digits, int scale) {
        int size = packedSize(digits);
        check(record, offset, size);
        StringBuilder sb = new StringBuilder(size * 2);
        for (int i = 0; i < size; i++) {
            int b = record[offset + i] & 0xFF;
            sb.append(nibble(b >>> 4, offset + i));
            if (i < size - 1) {
                sb.append(nibble(b & 0x0F, offset + i));
            }
        }
        int sign = record[offset + size - 1] & 0x0F;
        if (sign < 0xA) {
            throw new CodecException(String.format("invalid packed sign nibble 0x%X at offset %d", sign, offset + size - 1));
        }
        BigDecimal v = new BigDecimal(new BigInteger(sb.toString()), scale);
        return sign == 0xD || sign == 0xB ? v.negate() : v;
    }

    public void encodePacked(BigDecimal value, byte[] record, int offset, int digits, int scale, boolean signed) {
        int size = packedSize(digits);
        check(record, offset, size);
        String unscaled = pad(unscaledDigits(value, digits, scale, signed), size * 2 - 1);
        int sign = !signed ? 0xF : value.signum() < 0 ? 0xD : 0xC;
        for (int i = 0; i < size; i++) {
            int hi = unscaled.charAt(2 * i) - '0';
            int lo = i == size - 1 ? sign : unscaled.charAt(2 * i + 1) - '0';
            record[offset + i] = (byte) ((hi << 4) | lo);
        }
    }

    // ------------------------------------------------------------------ COMP / BINARY / COMP-5

    public BigDecimal decodeBinary(byte[] record, int offset, int size, int scale, boolean signed) {
        check(record, offset, size);
        byte[] raw = Arrays.copyOfRange(record, offset, offset + size);
        BigInteger v = signed ? new BigInteger(raw) : new BigInteger(1, raw);
        return new BigDecimal(v, scale);
    }

    public void encodeBinary(BigDecimal value, byte[] record, int offset, int size, int scale, boolean signed) {
        check(record, offset, size);
        BigInteger v = exactUnscaled(value, scale);
        if (!signed && v.signum() < 0) {
            throw new CodecException(value + " is negative but the field is unsigned");
        }
        int bits = size * 8 - (signed ? 1 : 0);
        if (v.abs().bitLength() > bits && !(signed && v.equals(BigInteger.ONE.shiftLeft(bits).negate()))) {
            throw new CodecException(value + " does not fit in " + size + " binary bytes");
        }
        byte[] full = v.toByteArray();
        byte fill = v.signum() < 0 ? (byte) 0xFF : 0;
        Arrays.fill(record, offset, offset + size, fill);
        int copy = Math.min(full.length, size);
        System.arraycopy(full, full.length - copy, record, offset + size - copy, copy);
    }

    // ------------------------------------------------------------------ records

    /** Decodes every field of a flat layout into name → String / BigDecimal (insertion order = layout order). */
    public Map<String, Object> decode(byte[] record, RecordLayout layout) {
        if (record.length != layout.length()) {
            throw new CodecException(layout.name() + " expects " + layout.length() + " bytes, got " + record.length);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (FieldSpec f : layout.fields()) {
            out.put(f.name(), switch (f.kind()) {
                case ALPHANUMERIC -> decodeAlphanumeric(record, f.offset(), f.size());
                case ZONED -> decodeZoned(record, f.offset(), f.digits(), f.scale(), f.signed());
                case RAW_DIGITS -> decodeRawDigits(record, f.offset(), f.digits());
                case PACKED -> decodePacked(record, f.offset(), f.digits(), f.scale());
                case BINARY -> decodeBinary(record, f.offset(), f.size(), f.scale(), f.signed());
            });
        }
        return out;
    }

    /** Encodes values into a new record; bytes not covered by a field and missing text fields are EBCDIC spaces. */
    public byte[] encode(Map<String, ?> values, RecordLayout layout) {
        byte[] record = new byte[layout.length()];
        Arrays.fill(record, spaceByte());
        for (FieldSpec f : layout.fields()) {
            Object v = values.get(f.name());
            switch (f.kind()) {
                case ALPHANUMERIC -> encodeAlphanumeric(v == null ? "" : v.toString(), record, f.offset(), f.size());
                case RAW_DIGITS -> encodeRawDigits(v == null ? pad("0", f.digits()) : v.toString(), record, f.offset(), f.digits());
                case ZONED -> encodeZoned(decimal(v, f), record, f.offset(), f.digits(), f.scale(), f.signed());
                case PACKED -> encodePacked(decimal(v, f), record, f.offset(), f.digits(), f.scale(), f.signed());
                case BINARY -> encodeBinary(decimal(v, f), record, f.offset(), f.size(), f.scale(), f.signed());
            }
        }
        return record;
    }

    // ------------------------------------------------------------------ helpers

    private static BigDecimal decimal(Object v, FieldSpec f) {
        if (v == null) {
            return BigDecimal.ZERO;
        }
        if (v instanceof BigDecimal bd) {
            return bd;
        }
        if (v instanceof BigInteger bi) {
            return new BigDecimal(bi);
        }
        if (v instanceof Long || v instanceof Integer || v instanceof Short) {
            return BigDecimal.valueOf(((Number) v).longValue());
        }
        if (v instanceof String s) {
            return new BigDecimal(s.trim());
        }
        throw new CodecException(f.name() + ": " + v.getClass().getName() + " is not accepted; use BigDecimal (never double/float)");
    }

    private static BigInteger exactUnscaled(BigDecimal value, int scale) {
        try {
            return value.setScale(scale, RoundingMode.UNNECESSARY).unscaledValue();
        } catch (ArithmeticException e) {
            throw new CodecException(value + " has more than " + scale + " decimal places", e);
        }
    }

    private static String unscaledDigits(BigDecimal value, int digits, int scale, boolean signed) {
        if (!signed && value.signum() < 0) {
            throw new CodecException(value + " is negative but the PIC clause is unsigned");
        }
        String s = exactUnscaled(value, scale).abs().toString();
        if (s.length() > digits) {
            throw new CodecException(value + " overflows " + digits + " digits (scale " + scale + ")");
        }
        return pad(s, digits);
    }

    private static char nibble(int n, int at) {
        if (n > 9) {
            throw new CodecException(String.format("invalid packed digit nibble 0x%X at offset %d", n, at));
        }
        return (char) ('0' + n);
    }

    private static String pad(String s, int width) {
        return s.length() >= width ? s : "0".repeat(width - s.length()) + s;
    }

    private static void check(byte[] record, int offset, int length) {
        if (offset < 0 || length <= 0 || offset + length > record.length) {
            throw new CodecException("field [" + offset + ", " + (offset + length) + ") outside record of " + record.length + " bytes");
        }
    }
}
