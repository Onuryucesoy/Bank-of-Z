package com.ibm.bankofz.core.mapper;

import com.ibm.bankofz.core.model.CobolDate;
import com.ibm.bankofz.core.model.ProcTran;
import com.ibm.bankofz.core.model.ProcTranRow;

/** PROCTRAN record &lt;-&gt; BANKZ.PROCTRAN row (CHAR(6) time, CHAR(12) reference). */
public final class ProcTranRowMapper {

    private ProcTranRowMapper() {
    }

    public static ProcTranRow toRow(ProcTran p) {
        return new ProcTranRow(p.eyecatcher(), p.sortCode(), String.format("%08d", p.accountNumber()),
                AccountRowMapper.local(p.date()), String.format("%06d", p.time()),
                String.format("%012d", p.reference()), p.type(), p.description(), p.amount());
    }

    public static ProcTran fromRow(ProcTranRow r) {
        return new ProcTran(r.eyecatcher(), r.sortCode(), Long.parseLong(r.accountNumber().trim()),
                CobolDate.of(r.date()), Integer.parseInt(r.time().trim()), Long.parseLong(r.reference().trim()),
                r.type(), r.description(), r.amount());
    }
}
