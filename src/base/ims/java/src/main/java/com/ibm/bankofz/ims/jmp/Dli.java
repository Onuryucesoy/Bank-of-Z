package com.ibm.bankofz.ims.jmp;

/**
 * The CBLTDLI calls the IMS COBOL programs make, with COBOL I/O areas as byte arrays.
 * Every method returns the two-character PCB status code ("  " = success).
 */
public interface Dli {
    /** GU on the I/O PCB: copies the message (LLZZ included) over the start of {@code ioArea}. */
    String getUniqueMessage(byte[] ioArea) throws Exception;

    /** ISRT on the I/O PCB of the first {@code length} bytes of {@code ioArea} (LLZZ included). */
    String insertMessage(byte[] ioArea, int length) throws Exception;

    /** The syncpoint COBOL gets implicitly from its next GU on the I/O PCB; JMP has to ask for it. */
    void syncpoint() throws Exception;

    /** A DB call (GU, GHU, GN, GHN, ...) against the PCB named {@code pcb} with one qualified SSA. */
    String call(String function, String pcb, byte[] ioArea, Ssa ssa) throws Exception;

    /** REPL of the segment last retrieved with a hold call on {@code pcb}. */
    String replace(String pcb, String segment, byte[] ioArea) throws Exception;
}
