package com.ibm.bankofz.parity.tape;

import java.util.Arrays;
import java.util.Objects;

/**
 * One recorded call: the bytes a program received and the bytes it returned. {@code requestLayout} and
 * {@code responseLayout} name a generated copybook layout ({@code CICS:PAYDBCR}, {@code IMS:IBTRAN/INPUT-AREA})
 * or {@code -} when the area has no copybook yet (compared byte-for-byte). {@code outcome} is {@code OK},
 * {@code ABEND:<code>}, {@code RESP:<n>} or {@code STATUS:<pcb status>}.
 */
public record Exchange(long seq, String system, String transaction, String program,
                       String requestLayout, String responseLayout, String outcome,
                       byte[] request, byte[] response) {

    public Exchange {
        Objects.requireNonNull(system);
        Objects.requireNonNull(program);
        transaction = transaction == null || transaction.isBlank() ? "-" : transaction;
        requestLayout = requestLayout == null || requestLayout.isBlank() ? "-" : requestLayout;
        responseLayout = responseLayout == null || responseLayout.isBlank() ? "-" : responseLayout;
        outcome = outcome == null || outcome.isBlank() ? "OK" : outcome;
        request = request == null ? new byte[0] : request.clone();
        response = response == null ? new byte[0] : response.clone();
    }

    @Override
    public byte[] request() {
        return request.clone();
    }

    @Override
    public byte[] response() {
        return response.clone();
    }

    public Exchange withResult(byte[] newResponse, String newOutcome) {
        return new Exchange(seq, system, transaction, program, requestLayout, responseLayout, newOutcome,
                request, newResponse);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Exchange e && seq == e.seq && system.equals(e.system)
                && transaction.equals(e.transaction) && program.equals(e.program)
                && requestLayout.equals(e.requestLayout) && responseLayout.equals(e.responseLayout)
                && outcome.equals(e.outcome) && Arrays.equals(request, e.request)
                && Arrays.equals(response, e.response);
    }

    @Override
    public int hashCode() {
        return Objects.hash(seq, system, program, Arrays.hashCode(request), Arrays.hashCode(response));
    }
}
