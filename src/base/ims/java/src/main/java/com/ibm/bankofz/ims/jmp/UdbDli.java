package com.ibm.bankofz.ims.jmp;

import com.ibm.ims.dli.AIB;
import com.ibm.ims.dli.DLIException;
import com.ibm.ims.dli.IMSConnectionSpec;
import com.ibm.ims.dli.IMSConnectionSpecFactory;
import com.ibm.ims.dli.PCB;
import com.ibm.ims.dli.PSB;
import com.ibm.ims.dli.PSBFactory;
import com.ibm.ims.dli.SSAList;
import com.ibm.ims.dli.tm.Application;
import com.ibm.ims.dli.tm.ApplicationFactory;
import com.ibm.ims.dli.tm.MessageQueue;
import com.ibm.ims.dli.tm.Transaction;
import com.ibm.ims.dli.wcaz.WcazSSAMetadata;
import com.ibm.ims.dli.wcaz.WcazSSAQualification;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link Dli} over the IMS Universal DL/I driver (type 2) in a JMP region. Segments and messages move
 * as raw byte buffers ({@code PCB.getUnique(ByteBuffer, SSAList, boolean)}, {@code MessageQueue.getUnique(ByteBuffer)}),
 * so the bytes are the COBOL I/O areas; SSAs are built with {@link WcazSSAMetadata}, without
 * Java metadata classes for the database. PSB metadata comes from the IMS catalog unless
 * {@code -Dbankz.ims.metadataURL.<PSB>} names a class.
 */
final class UdbDli implements Dli {
    private static final int MAX_MESSAGE = 32767;

    private final Application app;
    private final MessageQueue queue;
    private final Transaction tran;
    private final PSB psb;
    private final Map<String, PCB> pcbs = new HashMap<>();
    private ByteBuffer message;

    @SuppressWarnings("deprecation")
    UdbDli(String psbName) throws DLIException {
        app = ApplicationFactory.createApplication();
        queue = app.getMessageQueue();
        tran = app.getTransaction();
        IMSConnectionSpec spec = IMSConnectionSpecFactory.createIMSConnectionSpec();
        spec.setDriverType(IMSConnectionSpec.DRIVER_TYPE_2);
        String metadata = System.getProperty("bankz.ims.metadataURL." + psbName);
        if (metadata != null) {
            spec.setMetadataURL(metadata);
        }
        psb = PSBFactory.createPSB(spec);
    }

    @Override
    public String getUniqueMessage(byte[] ioArea) throws DLIException {
        if (message == null) {
            message = app.get31BitDirectByteBuffer(MAX_MESSAGE);
        }
        message.clear();
        if (!queue.getUnique(message)) {
            return "QC";
        }
        int ll = ((message.get(0) & 0xFF) << 8) | (message.get(1) & 0xFF);
        message.position(0);
        message.get(ioArea, 0, Math.min(ll, ioArea.length));
        return "  ";
    }

    @Override
    public String insertMessage(byte[] ioArea, int length) {
        try {
            ByteBuffer out = app.get31BitDirectByteBuffer(length);
            out.put(ioArea, 0, length).flip();
            queue.insert(out, MessageQueue.DEFAULT_DESTINATION);
            return "  ";
        } catch (DLIException e) {
            AIB aib = e.getAib();
            return aib == null || aib.getIOPCB() == null ? "XX" : aib.getIOPCB().getStatusCodeChars();
        }
    }

    @Override
    public void syncpoint() throws DLIException {
        tran.commit();
    }

    @Override
    public String replace(String pcbName, String segment, byte[] ioArea) throws DLIException {
        PCB pcb = pcb(pcbName);
        try {
            pcb.replace(ByteBuffer.wrap(ioArea), pcb.getSSAList(new WcazSSAMetadata(segment, List.<Byte>of(), List.of()) { }));
            String st = pcb.getAIB().getDBPCB().getStatusCodeChars();
            return st == null ? "  " : st;
        } catch (DLIException e) {
            return status(e);
        }
    }

    @Override
    public String insert(String pcbName, String segment, byte[] ioArea) throws DLIException {
        PCB pcb = pcb(pcbName);
        try {
            pcb.insert(ByteBuffer.wrap(ioArea), pcb.getSSAList(new WcazSSAMetadata(segment, List.<Byte>of(), List.of()) { }));
            String st = pcb.getAIB().getDBPCB().getStatusCodeChars();
            return st == null ? "  " : st;
        } catch (DLIException e) {
            return status(e);
        }
    }

    @Override
    public void rollback() throws Exception {
        tran.rollback();
    }

    private PCB pcb(String pcbName) throws DLIException {
        PCB pcb = pcbs.get(pcbName);
        if (pcb == null) {
            pcb = psb.getPCB(pcbName);
            pcbs.put(pcbName, pcb);
        }
        return pcb;
    }

    private static String status(DLIException e) {
        AIB aib = e.getAib();
        return aib == null || aib.getDBPCB() == null ? "XX" : aib.getDBPCB().getStatusCodeChars();
    }

    @Override
    public String call(String function, String pcbName, byte[] ioArea, Ssa ssa) throws DLIException {
        PCB pcb = pcb(pcbName);
        WcazSSAQualification q = new WcazSSAQualification();
        q.setFieldName(ssa.field());
        q.setFieldValue(ssa.value());
        q.setRelationalOperator(SSAList.EQUALS);
        SSAList ssaList = pcb.getSSAList(new WcazSSAMetadata(ssa.segment(), List.<Byte>of(), List.of(q)) { });
        ByteBuffer io = ByteBuffer.wrap(ioArea);
        boolean hold = function.startsWith("GH");
        try {
            boolean found = switch (function.trim()) {
                case "GU", "GHU" -> pcb.getUnique(io, ssaList, hold);
                case "GN", "GHN" -> pcb.getNext(io, ssaList, hold);
                default -> throw new IllegalArgumentException(function);
            };
            return found ? "  " : pcb.getAIB().getDBPCB().getStatusCodeChars();
        } catch (DLIException e) {
            return status(e);
        }
    }
}
