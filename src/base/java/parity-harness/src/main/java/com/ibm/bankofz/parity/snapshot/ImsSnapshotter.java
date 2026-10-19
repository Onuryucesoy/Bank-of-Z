package com.ibm.bankofz.parity.snapshot;

import com.ibm.bankofz.bridge.runtime.Binary;
import com.ibm.bankofz.bridge.runtime.CobolDataException;
import com.ibm.bankofz.bridge.runtime.Hex;
import com.ibm.bankofz.bridge.runtime.PackedDecimal;
import com.ibm.bankofz.bridge.runtime.Text;
import com.ibm.bankofz.parity.snapshot.Snapshot.Column;
import com.ibm.bankofz.parity.snapshot.Snapshot.ColumnKind;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Snapshots an IMS database from an unload of its segments: fixed-length records of exactly
 * {@code SEGM BYTES}, as written by {@code SegmentUnload} (a GN sweep through PSB IB). Fields are decoded
 * with the DBD's own FIELD/DATATYPE definitions; bytes no FIELD covers are kept as hex so nothing is lost.
 */
public final class ImsSnapshotter {
    private static final Pattern DECIMAL = Pattern.compile("DECIMAL\\((\\d+),(\\d+)\\)");

    private ImsSnapshotter() {
    }

    public static void snapshot(Dbd dbd, String segName, InputStream unload, Snapshot into) throws IOException {
        Dbd.Segment seg = dbd.segment(segName);
        Charset cs = Charset.forName(dbd.encoding());
        List<Slot> slots = slots(seg);
        List<Column> cols = slots.stream().map(s -> new Column(s.name, s.kind)).toList();
        Snapshot.Table t = into.table(dbd.name() + "/" + seg.name(), cols);
        Dbd.Field seq = seg.sequenceField();
        DataInputStream in = new DataInputStream(unload);
        byte[] rec = new byte[seg.bytes()];
        while (true) {
            try {
                in.readFully(rec);
            } catch (EOFException e) {
                break;
            }
            List<String> vals = new ArrayList<>();
            String key = null;
            for (Slot s : slots) {
                String v = s.render(rec, cs);
                vals.add(v);
                if (seq != null && s.field == seq) {
                    key = v;
                }
            }
            t.put(key != null ? key : Hex.of(rec), vals);
        }
    }

    private record Slot(String name, ColumnKind kind, Dbd.Field field, int offset, int length) {
        String render(byte[] rec, Charset cs) {
            if (field == null) {
                return Hex.of(rec, offset, length);
            }
            try {
                Matcher m = DECIMAL.matcher(field.datatype());
                if (m.matches()) {
                    int digits = Integer.parseInt(m.group(1));
                    if (PackedDecimal.byteLength(digits) != length) {
                        throw new IllegalStateException(field.name() + ": " + field.datatype() + " needs "
                                + PackedDecimal.byteLength(digits) + " bytes, DBD says " + length);
                    }
                    return PackedDecimal.decode(rec, offset, digits, Integer.parseInt(m.group(2))).toPlainString();
                }
                return switch (field.datatype()) {
                    case "INT" -> Binary.decode(rec, offset, 9, 0, true).toPlainString();
                    case "LONG" -> Binary.decode(rec, offset, 18, 0, true).toPlainString();
                    case "BINARY" -> Hex.of(rec, offset, length);
                    default -> Text.decode(rec, offset, length, cs);
                };
            } catch (CobolDataException e) {
                return "!HEX:" + Hex.of(rec, offset, length);
            }
        }
    }

    private static List<Slot> slots(Dbd.Segment seg) {
        List<Dbd.Field> fields = new ArrayList<>(seg.fields());
        fields.sort(java.util.Comparator.comparingInt(Dbd.Field::offset));
        List<Slot> out = new ArrayList<>();
        int pos = 0;
        for (Dbd.Field f : fields) {
            if (f.offset() < pos) {
                throw new IllegalStateException(seg.name() + "." + f.name() + " overlaps the previous field");
            }
            if (f.offset() > pos) {
                out.add(new Slot("FILLER@" + (pos + 1), ColumnKind.BINARY, null, pos, f.offset() - pos));
            }
            ColumnKind kind = f.datatype().startsWith("DECIMAL") ? ColumnKind.DECIMAL
                    : f.datatype().equals("INT") || f.datatype().equals("LONG") ? ColumnKind.INTEGER
                    : f.datatype().equals("BINARY") ? ColumnKind.BINARY : ColumnKind.TEXT;
            out.add(new Slot(f.name(), kind, f, f.offset(), f.bytes()));
            pos = f.offset() + f.bytes();
        }
        if (pos < seg.bytes()) {
            out.add(new Slot("FILLER@" + (pos + 1), ColumnKind.BINARY, null, pos, seg.bytes() - pos));
        }
        if (pos > seg.bytes()) {
            throw new IllegalStateException(seg.name() + ": fields end at " + pos + ", BYTES=" + seg.bytes());
        }
        return out;
    }
}
