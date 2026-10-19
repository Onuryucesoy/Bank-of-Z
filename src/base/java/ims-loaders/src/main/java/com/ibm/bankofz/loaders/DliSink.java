package com.ibm.bankofz.loaders;

import com.ibm.ims.dli.DLIException;
import com.ibm.ims.dli.IMSConnectionSpec;
import com.ibm.ims.dli.IMSConnectionSpecFactory;
import com.ibm.ims.dli.PCB;
import com.ibm.ims.dli.PSB;
import com.ibm.ims.dli.PSBFactory;
import com.ibm.ims.dli.Path;
import com.ibm.ims.dli.SSAList;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * ISRT through the IMS Universal DL/I driver, type 2 (in an IMS Java region on z/OS). The PSB is IBLOAD,
 * unchanged; the driver needs its database metadata class ({@code -Dbankz.ims.metadataURL=class://...}),
 * generated from the DBDs with the loader segment layouts so the field names are the COBOL ones
 * (ACCID, ACCTYPE, ...). Each field gets the exact bytes the COBOL program put in its I/O area.
 */
final class DliSink implements SegmentSink {
    private final PSB psb;
    private final Map<String, PCB> pcbs = new HashMap<>();

    @SuppressWarnings("deprecation")
    DliSink(String psbName) throws DLIException {
        IMSConnectionSpec spec = IMSConnectionSpecFactory.createIMSConnectionSpec();
        spec.setDriverType(IMSConnectionSpec.DRIVER_TYPE_2);
        spec.setDatastoreName(System.getProperty("bankz.ims.datastore", "IVP1"));
        String metadata = System.getProperty("bankz.ims.metadataURL");
        if (metadata == null) {
            throw new DLIException("set -Dbankz.ims.metadataURL for PSB " + psbName);
        }
        spec.setMetadataURL(metadata);
        psb = PSBFactory.createPSB(spec);
        psb.open();
    }

    @Override
    public String insert(LoaderSpec spec, byte[] segment) {
        try {
            PCB pcb = pcbs.computeIfAbsent(spec.pcb(), name -> {
                try {
                    return psb.getPCB(name);
                } catch (DLIException e) {
                    throw new IllegalStateException(e);
                }
            });
            SSAList ssa = pcb.getSSAList(spec.segment());
            Path path = ssa.getPathForInsert(spec.segment());
            int off = 0;
            for (LoaderSpec.Seg f : spec.fields()) {
                path.setBytes(spec.segment(), f.name(), Arrays.copyOfRange(segment, off, off + f.length()));
                off += f.length();
            }
            short rc = pcb.insert(path, ssa);
            return rc == 0 ? "  " : String.format("%04X", rc);
        } catch (DLIException e) {
            return e.getAib() == null ? "DL" : e.getAib().getReturnCodeHex();
        }
    }

    @Override
    public void close() throws java.io.IOException {
        try {
            psb.commit();
            psb.close();
        } catch (DLIException e) {
            throw new java.io.IOException(e);
        }
    }
}
