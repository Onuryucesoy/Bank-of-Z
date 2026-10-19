package com.ibm.bankofz.loaders;

/** Where an ISRT goes. Returns the two-character DL/I status code; blanks mean success. */
interface SegmentSink extends AutoCloseable {
    String insert(LoaderSpec spec, byte[] segment) throws Exception;

    @Override
    default void close() throws java.io.IOException {
    }
}
