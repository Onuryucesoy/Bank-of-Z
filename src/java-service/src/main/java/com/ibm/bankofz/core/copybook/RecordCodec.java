package com.ibm.bankofz.core.copybook;

import com.ibm.bankofz.core.codec.Binary;
import com.ibm.bankofz.core.codec.CodecException;
import com.ibm.bankofz.core.codec.Ebcdic;
import com.ibm.bankofz.core.codec.PackedDecimal;
import com.ibm.bankofz.core.codec.ZonedDecimal;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Layout-driven codec between a field map and the exact byte image of a
 * copybook record (COMMAREA, container or Db2/VSAM record) in EBCDIC.
 * Values are strings: PIC X text, or plain decimal numbers for PIC 9.
 * Unset items take COBOL INITIALIZE defaults (spaces / zero). OCCURS
 * DEPENDING ON tables are always laid out at their maximum size, matching
 * how the CICS callers allocate COMMAREAs.
 */
public final class RecordCodec {

    private final Field record;
    private final Map<String, Copybook.Slot> slots;
    private final Ebcdic text;

    public RecordCodec(Field record, Ebcdic text) {
        this.record = record;
        this.slots = Copybook.slots(record);
        this.text = text;
    }

    public RecordCodec(Field record) {
        this(record, Ebcdic.cp037());
    }

    public Field record() {
        return record;
    }

    public int length() {
        return record.length();
    }

    public Map<String, Copybook.Slot> slots() {
        return slots;
    }

    public byte[] encode(Map<String, String> values) {
        for (String k : values.keySet()) {
            if (!slots.containsKey(k)) {
                throw new CodecException(record.name() + " has no elementary item " + k);
            }
        }
        byte[] out = new byte[record.length()];
        for (Copybook.Slot s : slots.values()) {
            if (!s.field().underRedefines()) {
                put(out, s, values.get(s.key()));
            }
        }
        for (Copybook.Slot s : slots.values()) {
            if (s.field().underRedefines() && values.containsKey(s.key())) {
                put(out, s, values.get(s.key()));
            }
        }
        return out;
    }

    /**
     * Decodes every non-FILLER item. Items under REDEFINES are included only
     * when their bytes are valid for their PIC.
     */
    public Map<String, String> decode(byte[] data) {
        if (data.length < record.length()) {
            throw new CodecException(record.name() + " needs " + record.length() + " bytes, got " + data.length);
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Copybook.Slot s : slots.values()) {
            if (s.field().isFiller() || s.field().usage() == Usage.POINTER) {
                continue;
            }
            try {
                out.put(s.key(), get(data, s));
            } catch (CodecException e) {
                if (!s.field().underRedefines()) {
                    throw new CodecException(s.key() + ": " + e.getMessage(), e);
                }
            }
        }
        return out;
    }

    private void put(byte[] out, Copybook.Slot s, String value) {
        Field f = s.field();
        byte[] bytes;
        if (f.usage() == Usage.POINTER) {
            bytes = new byte[8];
        } else if (!f.pic().numeric()) {
            bytes = text.encodeExact(value, f.length());
        } else {
            BigDecimal v = value == null || value.isBlank() ? BigDecimal.ZERO : new BigDecimal(value.trim());
            Pic p = f.pic();
            try {
                bytes = switch (f.usage()) {
                    case PACKED -> PackedDecimal.encode(v, p.digits(), p.scale(), p.signed());
                    case BINARY -> Binary.encode(v, p.digits(), p.scale(), p.signed(), false);
                    case NATIVE_BINARY -> Binary.encode(v, p.digits(), p.scale(), p.signed(), true);
                    default -> {
                        yield f.signSeparate()
                                ? ZonedDecimal.encodeSignSeparate(v, p.digits(), p.scale(), f.signLeading())
                                : ZonedDecimal.encode(v, p.digits(), p.scale(), p.signed());
                    }
                };
            } catch (CodecException e) {
                throw new CodecException(s.key() + " PIC " + p.clause() + ": " + e.getMessage(), e);
            }
        }
        System.arraycopy(bytes, 0, out, s.offset(), bytes.length);
    }

    private String get(byte[] data, Copybook.Slot s) {
        Field f = s.field();
        if (!f.pic().numeric()) {
            return text.decode(data, s.offset(), f.length());
        }
        Pic p = f.pic();
        BigDecimal v = switch (f.usage()) {
            case PACKED -> PackedDecimal.decode(data, s.offset(), p.digits(), p.scale());
            case BINARY, NATIVE_BINARY -> Binary.decode(data, s.offset(), p.digits(), p.scale(), p.signed());
            default -> f.signSeparate()
                    ? ZonedDecimal.decodeSignSeparate(data, s.offset(), p.digits(), p.scale(), f.signLeading())
                    : ZonedDecimal.decode(data, s.offset(), p.digits(), p.scale());
        };
        return v.toPlainString();
    }

    /** Offset-to-item lookup for byte-level diff reporting. */
    public String itemAt(int offset) {
        return slots.values().stream()
                .filter(s -> !s.field().underRedefines())
                .filter(s -> offset >= s.offset() && offset < s.offset() + s.field().length())
                .map(Copybook.Slot::key).findFirst().orElse("<unmapped>");
    }

    public static int firstDifference(byte[] a, byte[] b) {
        int m = Arrays.mismatch(a, b);
        return m;
    }
}
