package com.ibm.bankofz.bridge.runtime;

import com.ibm.bankofz.bridge.copybook.cics.CicsCopybooks;
import com.ibm.bankofz.bridge.copybook.ims.ImsCopybooks;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * For every generated layout: random valid bytes -> decode every canonical field -> encode into a fresh buffer
 * must reproduce the original bytes exactly. This is the decode/encode identity the COMMAREA bridge relies on.
 */
class RoundTripTest {
    private static final long SEED = 20261019L;

    static final class View extends CobolRecord {
        View(RecordLayout l, byte[] b) {
            super(l, b, 0);
        }

        void copyFieldFrom(View src, FieldSpec f, int... idx) {
            switch (f.kind()) {
                case ALPHANUMERIC, EDITED -> setString(f, src.getString(f, idx), idx);
                case POINTER -> setRaw(f, src.getBytes(f, idx), idx);
                default -> setDecimal(f, src.getDecimal(f, idx), false, idx);
            }
        }
    }

    @Test
    void decodeEncodeIsIdentityForAllLayouts() {
        Random rnd = new Random(SEED);
        List<RecordLayout> all = new ArrayList<>(CicsCopybooks.LAYOUTS.values());
        all.addAll(ImsCopybooks.LAYOUTS.values());
        int fields = 0;
        for (RecordLayout l : all) {
            for (int round = 0; round < 20; round++) {
                byte[] original = new byte[l.length()];
                for (FieldSpec f : l.canonicalLeaves()) {
                    forEach(f, idx -> fill(l, f, original, f.offsetOf(idx), rnd));
                }
                View src = new View(l, original);
                View dst = new View(l, new byte[l.length()]);
                for (FieldSpec f : l.canonicalLeaves()) {
                    forEach(f, idx -> dst.copyFieldFrom(src, f, idx));
                    fields++;
                }
                assertArrayEquals(original, dst.toBytes(), l.name() + " round " + round);
                Map<String, String> a = l.render(original, 0);
                assertEquals(a, l.render(dst.toBytes(), 0));
            }
        }
        assertEquals(true, fields > 1000);
    }

    private static void fill(RecordLayout l, FieldSpec f, byte[] buf, int at, Random rnd) {
        switch (f.kind()) {
            case ALPHANUMERIC, EDITED -> {
                String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ abcdefghijklmnopqrstuvwxyz0123456789-./";
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < f.length(); i++) {
                    sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
                }
                Text.encode(sb.toString(), buf, at, f.length(), l.charset());
            }
            case POINTER -> {
                for (int i = 0; i < f.length(); i++) {
                    buf[at + i] = (byte) rnd.nextInt(256);
                }
            }
            default -> {
                int digits = f.kind() == FieldSpec.Kind.BINARY_NATIVE ? Math.min(f.digits(), 18) : f.digits();
                BigInteger u = new BigInteger(digits * 4, rnd).mod(BigInteger.TEN.pow(digits));
                if (f.kind() == FieldSpec.Kind.BINARY_NATIVE && f.length() == 2) {
                    u = u.mod(BigInteger.valueOf(32768));
                } else if (f.kind() == FieldSpec.Kind.BINARY_NATIVE && f.length() == 4) {
                    u = u.mod(BigInteger.valueOf(Integer.MAX_VALUE));
                }
                if (f.signed() && rnd.nextBoolean()) {
                    u = u.negate();
                }
                BigDecimal v = new BigDecimal(u, f.scale());
                switch (f.kind()) {
                    case ZONED -> ZonedDecimal.encode(v, buf, at, f.digits(), f.scale(), f.sign());
                    case PACKED -> PackedDecimal.encode(v, buf, at, f.digits(), f.scale(), f.signed());
                    case BINARY -> Binary.encode(v, buf, at, f.digits(), f.scale(), f.signed(), false);
                    default -> Binary.encode(v, buf, at, f.digits(), f.scale(), f.signed(), true);
                }
            }
        }
    }

    private static void forEach(FieldSpec f, java.util.function.Consumer<int[]> action) {
        walk(f, new int[f.dimensions()], 0, action);
    }

    private static void walk(FieldSpec f, int[] idx, int d, java.util.function.Consumer<int[]> action) {
        if (d == idx.length) {
            action.accept(idx.clone());
            return;
        }
        for (int i = 0; i < f.maxOccurs(d); i++) {
            idx[d] = i;
            walk(f, idx, d + 1, action);
        }
    }
}
