package com.ibm.bankofz.capture;

import com.ibm.bankofz.parity.Catalog;
import com.ibm.bankofz.parity.tape.Exchange;
import com.ibm.bankofz.parity.tape.Tape;
import com.ibm.cics.server.AbendException;
import com.ibm.cics.server.Channel;
import com.ibm.cics.server.CommAreaHolder;
import com.ibm.cics.server.Container;
import com.ibm.cics.server.Program;
import com.ibm.cics.server.Task;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Properties;

/**
 * Capture proxy for Phase 0. Installed (PROGRAM ... JVMCLASS(com.ibm.bankofz.capture.CaptureProxy)) under
 * the name callers LINK to, with the legacy program re-defined under the alias in
 * {@code capture-targets.properties}. It copies the COMMAREA (or the catalog's container), LINKs to the
 * legacy program, records request/response bytes and the outcome to a tape in {@code bankz.capture.dir},
 * and returns the legacy result unchanged, so callers see identical behavior and the same unit of work.
 */
public final class CaptureProxy {
    private static final Properties TARGETS = load();

    private CaptureProxy() {
    }

    public static void main(CommAreaHolder holder) throws Exception {
        Task task = Task.getTask();
        String self = task.getProgramName().trim();
        String alias = TARGETS.getProperty(self);
        if (alias == null) {
            throw new IllegalStateException("no capture target for " + self);
        }
        Catalog.Entry entry = Catalog.find("CICS", self)
                .orElseThrow(() -> new IllegalStateException(self + " is not in the capture catalog"));
        Program target = new Program();
        target.setName(alias);

        byte[] request;
        byte[] response;
        String outcome = "OK";
        AbendException abend = null;
        if (entry.iface().startsWith("CHANNEL:")) {
            String container = entry.iface().substring(entry.iface().indexOf('/') + 1);
            Channel channel = task.getCurrentChannel();
            Container c = channel.getContainer(container);
            request = c.get();
            try {
                target.link(channel);
            } catch (AbendException e) {
                abend = e;
                outcome = "ABEND:" + e.getABCODE();
            }
            response = channel.getContainer(container).get();
        } else {
            byte[] commarea = holder == null ? new byte[0] : holder.getValue();
            request = commarea.clone();
            try {
                target.link(commarea);
            } catch (AbendException e) {
                abend = e;
                outcome = "ABEND:" + e.getABCODE();
            }
            response = commarea.clone();
        }
        long seq = System.currentTimeMillis() * 100_000L + task.getTaskNumber() % 100_000;
        append(self, new Exchange(seq, "CICS", task.getTransactionName(), self, entry.requestLayout(),
                entry.responseLayout(), outcome, request, response));
        if (abend != null) {
            throw abend;
        }
    }

    static synchronized void append(String program, Exchange e) {
        Path dir = Path.of(System.getProperty("bankz.capture.dir", "/tmp/bankz-capture"));
        Path file = dir.resolve(program + ".tape");
        try {
            Files.createDirectories(dir);
            boolean fresh = !Files.exists(file);
            try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                FileLock lock = ch.lock();
                try {
                    String text = (fresh ? Tape.HEADER + "\n" : "") + Tape.line(e) + "\n";
                    ch.write(ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8)));
                } finally {
                    lock.release();
                }
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static Properties load() {
        Properties p = new Properties();
        try (InputStream in = CaptureProxy.class.getResourceAsStream("capture-targets.properties")) {
            if (in != null) {
                p.load(in);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return p;
    }
}
