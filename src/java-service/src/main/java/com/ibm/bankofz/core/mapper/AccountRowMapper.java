package com.ibm.bankofz.core.mapper;

import com.ibm.bankofz.core.model.Account;
import com.ibm.bankofz.core.model.AccountRow;
import com.ibm.bankofz.core.model.CobolDate;

import java.time.LocalDate;

/**
 * ACCOUNT record &lt;-&gt; BANKZ.ACCOUNT row. COBOL moves DATE columns
 * through 'DD.MM.YYYY' host variables; here they map to {@link LocalDate}.
 * A zero COBOL date has no Db2 DATE equivalent and maps to NULL.
 */
public final class AccountRowMapper {

    private AccountRowMapper() {
    }

    public static AccountRow toRow(Account a) {
        return new AccountRow(a.eyecatcher(), String.format("%010d", a.customerNumber()), a.sortCode(),
                String.format("%08d", a.accountNumber()), a.accountType(), a.interestRate(),
                local(a.opened()), Math.toIntExact(a.overdraftLimit()), local(a.lastStatementDate()),
                local(a.nextStatementDate()), a.availableBalance(), a.actualBalance());
    }

    public static Account fromRow(AccountRow r) {
        return new Account(r.eyecatcher(), Long.parseLong(r.customerNumber().trim()), r.sortCode(),
                Long.parseLong(r.accountNumber().trim()), r.accountType(), r.interestRate(),
                CobolDate.of(r.opened()), r.overdraftLimit() == null ? 0 : r.overdraftLimit(),
                CobolDate.of(r.lastStatement()), CobolDate.of(r.nextStatement()),
                r.availableBalance(), r.actualBalance());
    }

    static LocalDate local(CobolDate d) {
        if (d == null || d.isZero()) {
            return null;
        }
        return d.toLocalDate().orElseThrow(() -> new IllegalArgumentException("not a calendar date: " + d));
    }
}
