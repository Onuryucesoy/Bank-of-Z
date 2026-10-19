package com.ibm.bankofz.cics;

import java.time.LocalDateTime;

/** The task-level CICS services (EIBTASKN, ASKTIME/FORMATTIME, ABEND) used by the ported programs. */
public interface CicsTask {
    int taskNumber();

    LocalDateTime now();

    /** EXEC CICS ABEND; never returns normally. The returned exception is for {@code throw} at the call site. */
    RuntimeException abend(String code);

    /** EXEC CICS LINK PROGRAM(program) COMMAREA(commarea); the COMMAREA is updated in place. */
    default void link(String program, byte[] commarea) {
        throw new UnsupportedOperationException("LINK " + program);
    }

    /** EXEC CICS SYNCPOINT ROLLBACK; the callers abend HROL if it fails. */
    default void rollback() {
        throw new UnsupportedOperationException("SYNCPOINT ROLLBACK");
    }

    static CicsTask jcics() {
        com.ibm.cics.server.Task task = com.ibm.cics.server.Task.getTask();
        return new CicsTask() {
            @Override
            public int taskNumber() {
                return task.getTaskNumber();
            }

            @Override
            public LocalDateTime now() {
                return LocalDateTime.now();
            }

            @Override
            public void link(String program, byte[] commarea) {
                try {
                    new com.ibm.cics.server.Program(program).link(commarea);
                } catch (com.ibm.cics.server.CicsConditionException e) {
                    throw new IllegalStateException("LINK " + program, e);
                }
            }

            @Override
            public void rollback() {
                try {
                    task.rollback();
                } catch (com.ibm.cics.server.InvalidRequestException e) {
                    throw abend("HROL");
                }
            }

            @Override
            public RuntimeException abend(String code) {
                task.abend(code);
                return new IllegalStateException("ABEND " + code + " returned");
            }
        };
    }
}
