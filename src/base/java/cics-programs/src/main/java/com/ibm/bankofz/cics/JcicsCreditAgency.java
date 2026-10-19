package com.ibm.bankofz.cics;

import com.ibm.cics.server.Channel;
import com.ibm.cics.server.Container;
import com.ibm.cics.server.Task;

/**
 * JCICS binding. JCICS has no EXEC CICS DELAY equivalent, so the delay is a {@code Thread.sleep} on the
 * JVM server thread (the task stays dispatched instead of being suspended by the CICS dispatcher).
 */
final class JcicsCreditAgency implements CreditAgency.Cics {
    private final Task task = Task.getTask();

    static void main(String container) throws Exception {
        CreditAgency.run(new JcicsCreditAgency(), container, System.out);
    }

    @Override
    public int taskNumber() {
        return task.getTaskNumber();
    }

    @Override
    public void delay(int seconds) throws InterruptedException {
        Thread.sleep(seconds * 1000L);
    }

    @Override
    public byte[] getContainer(String name) {
        try {
            Channel ch = task.getCurrentChannel();
            Container c = ch == null ? null : ch.getContainer(name);
            return c == null ? null : c.get();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public boolean putContainer(String name, byte[] data) {
        try {
            Channel ch = task.getCurrentChannel();
            Container c = ch.getContainer(name);
            (c == null ? ch.createContainer(name) : c).put(data);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void abend(String code) {
        task.abend(code);
    }
}
