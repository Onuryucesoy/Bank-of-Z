package com.ibm.bankofz.cics;

import com.ibm.bankofz.bridge.copybook.cics.Crecust;
import com.ibm.bankofz.bridge.runtime.ZonedDecimal;
import com.ibm.cics.server.AsyncService;
import com.ibm.cics.server.AsyncServiceImpl;
import com.ibm.cics.server.Channel;
import com.ibm.cics.server.ChildResponse;
import com.ibm.cics.server.CicsConditionException;
import com.ibm.cics.server.NotFinishedException;
import com.ibm.cics.server.NotFoundException;
import com.ibm.cics.server.Task;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * CREDIT-CHECK on JCICS: PUT the COMMAREA into CIPA-CIPE on channel CIPCREDCHANN, RUN TRANSID OCR1-OCR5 as
 * children, wait 3 seconds, then FETCH ANY NOSUSPEND until no child is left or one is still running.
 */
final class JcicsCreditCheck implements CreateCustomer.CreditCheck {
    private static final int CHILDREN = 5;

    @Override
    public CreateCustomer.Credit check(byte[] comm) {
        List<Future<ChildResponse>> children = new ArrayList<>();
        AsyncService async = new AsyncServiceImpl();
        try {
            Channel channel = Task.getTask().createChannel(CreditAgency.CHANNEL);
            for (int i = 0; i < CHILDREN; i++) {
                try {
                    channel.createContainer(container(i)).put(comm);
                } catch (CicsConditionException e) {
                    return fail('A');
                }
                try {
                    children.add(async.runTransactionId("OCR" + (i + 1), channel));
                } catch (CicsConditionException e) {
                    return fail('B');
                }
            }
        } catch (CicsConditionException e) {
            return fail('A');
        }
        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        int total = 0;
        int retrieved = 0;
        while (true) {
            ChildResponse child;
            try {
                child = async.getAny(AsyncService.BlockingAction.NOSUSPEND);
            } catch (NotFinishedException e) {
                return retrieved == 0 ? fail('C') : new CreateCustomer.Credit(' ', total, retrieved);
            } catch (NotFoundException e) {
                return new CreateCustomer.Credit(' ', total, retrieved);
            } catch (CicsConditionException e) {
                return fail('D');
            }
            ChildResponse.CompletionStatus status = child.getCompletionStatus();
            if (status == ChildResponse.CompletionStatus.ABEND) {
                return fail('F');
            }
            if (status == ChildResponse.CompletionStatus.SECERROR) {
                return fail('G');
            }
            if (status != ChildResponse.CompletionStatus.NORMAL) {
                return fail('H');
            }
            try {
                byte[] data = child.getChannel().getContainer(container(indexOf(children, child))).get();
                total += ZonedDecimal.decode(data, Crecust.COMM_CREDIT_SCORE.offset(), 3, 0, ZonedDecimal.Sign.NONE)
                        .intValueExact();
                retrieved++;
            } catch (CicsConditionException | RuntimeException e) {
                return fail('E');
            }
        }
    }

    private static int indexOf(List<Future<ChildResponse>> children, ChildResponse child) {
        for (int i = 0; i < children.size(); i++) {
            Future<ChildResponse> f = children.get(i);
            try {
                if (f.isDone() && f.get().equals(child)) {
                    return i;
                }
            } catch (InterruptedException | ExecutionException e) {
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalStateException("unknown child");
    }

    private static String container(int i) {
        return "CIP" + (char) ('A' + i);
    }

    private static CreateCustomer.Credit fail(char code) {
        return new CreateCustomer.Credit(code, 0, 0);
    }
}
