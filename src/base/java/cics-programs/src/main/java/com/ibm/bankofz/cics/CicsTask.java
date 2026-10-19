package com.ibm.bankofz.cics;

import java.time.LocalDateTime;

/** The task-level CICS services (EIBTASKN, ASKTIME/FORMATTIME, ABEND) used by the ported programs. */
public interface CicsTask {
    int taskNumber();

    LocalDateTime now();

    /** EXEC CICS ABEND; never returns normally. The returned exception is for {@code throw} at the call site. */
    RuntimeException abend(String code);

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
            public RuntimeException abend(String code) {
                task.abend(code);
                return new IllegalStateException("ABEND " + code + " returned");
            }
        };
    }
}
