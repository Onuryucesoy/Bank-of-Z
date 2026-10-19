package com.ibm.bankofz.ims.jmp;

/** The Db2 side of IBTRAN's history write (JAVA-SAVEHIST); receives the 64-byte HISTORY-SEG image. */
@FunctionalInterface
public interface HistorySink {
    void save(byte[] historySeg) throws Exception;

    /** The existing JNI target, unchanged: {@code nazare.jmp.controller.InsertHist.insertHist}. */
    static HistorySink insertHist() {
        return seg -> {
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocateDirect(seg.length);
            buf.put(seg).flip();
            nazare.jmp.controller.InsertHist.insertHist(buf);
        };
    }
}
