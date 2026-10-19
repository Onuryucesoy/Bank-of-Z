package com.ibm.bankofz.loaders;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Writes each segment image as a fixed-length record (SEGM BYTES), the format
 * {@code ParityGate snapshot-ims} reads, so the Java loader's output can be diffed against an
 * unload of the database the COBOL loader built, without touching IMS.
 */
final class UnloadSink implements SegmentSink {
    private final OutputStream out;

    UnloadSink(OutputStream out) {
        this.out = out;
    }

    @Override
    public String insert(LoaderSpec spec, byte[] segment) throws IOException {
        out.write(segment);
        return "  ";
    }

    @Override
    public void close() throws IOException {
        out.close();
    }
}
