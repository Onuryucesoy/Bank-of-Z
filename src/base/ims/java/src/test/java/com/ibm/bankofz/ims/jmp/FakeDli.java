package com.ibm.bankofz.ims.jmp;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** In-memory DL/I: root segments in key order per PCB, qualified SSA = key bytes equal. */
final class FakeDli implements Dli {
    final Map<String, List<byte[]>> db = new HashMap<>();
    final Map<String, String> forced = new HashMap<>();
    final Deque<Object> input = new ArrayDeque<>();
    final List<byte[]> output = new ArrayList<>();
    final List<Ssa> ssas = new ArrayList<>();
    int syncpoints;
    private final Map<String, Integer> position = new HashMap<>();
    private final Map<String, Boolean> held = new HashMap<>();
    final Map<String, String> forcedReplace = new HashMap<>();
    final Map<String, String> forcedInsert = new HashMap<>();
    int rollbacks;

    FakeDli segment(String pcb, byte[] seg) {
        db.computeIfAbsent(pcb, k -> new ArrayList<>()).add(seg);
        return this;
    }

    /** Queues a message (byte[]) or a GU status (String). */
    FakeDli message(Object m) {
        input.add(m);
        return this;
    }

    @Override
    public String getUniqueMessage(byte[] ioArea) {
        Object m = input.poll();
        if (m == null) {
            return "QC";
        }
        if (m instanceof String status) {
            return status;
        }
        byte[] msg = (byte[]) m;
        System.arraycopy(msg, 0, ioArea, 0, Math.min(msg.length, ioArea.length));
        return "  ";
    }

    @Override
    public String insertMessage(byte[] ioArea, int length) {
        output.add(Arrays.copyOf(ioArea, length));
        return "  ";
    }

    @Override
    public void syncpoint() {
        syncpoints++;
    }

    @Override
    public String call(String function, String pcb, byte[] ioArea, Ssa ssa) {
        ssas.add(ssa);
        if (forced.containsKey(pcb)) {
            return forced.get(pcb);
        }
        List<byte[]> segs = db.getOrDefault(pcb, List.of());
        int start = function.trim().endsWith("U") ? 0 : position.getOrDefault(pcb, -1) + 1;
        byte[] key = ssa.value();
        for (int i = start; i < segs.size(); i++) {
            byte[] s = segs.get(i);
            if (Arrays.equals(s, ssa.fieldOffset(), ssa.fieldOffset() + key.length, key, 0, key.length)) {
                System.arraycopy(s, 0, ioArea, 0, s.length);
                position.put(pcb, i);
                held.put(pcb, function.startsWith("GH"));
                return "  ";
            }
        }
        return "GE";
    }

    @Override
    public String replace(String pcb, String segment, byte[] ioArea) {
        if (forcedReplace.containsKey(pcb)) {
            return forcedReplace.get(pcb);
        }
        if (!held.getOrDefault(pcb, false)) {
            return "DJ";
        }
        db.get(pcb).set(position.get(pcb), ioArea.clone());
        held.put(pcb, false);
        return "  ";
    }

    @Override
    public String insert(String pcb, String segment, byte[] ioArea) {
        if (forcedInsert.containsKey(pcb)) {
            return forcedInsert.get(pcb);
        }
        db.computeIfAbsent(pcb, k -> new java.util.ArrayList<>()).add(ioArea.clone());
        return "  ";
    }

    @Override
    public void rollback() {
        rollbacks++;
    }
}
