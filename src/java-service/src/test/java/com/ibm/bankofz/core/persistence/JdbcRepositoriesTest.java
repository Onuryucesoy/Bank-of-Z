package com.ibm.bankofz.core.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ibm.bankofz.core.model.Account;
import com.ibm.bankofz.core.model.AccountKey;
import com.ibm.bankofz.core.model.CobolDate;
import com.ibm.bankofz.core.model.Customer;
import com.ibm.bankofz.core.model.CustomerKey;
import com.ibm.bankofz.core.model.ProcTran;

import java.math.BigDecimal;
import java.util.List;
import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JdbcRepositoriesTest {

    private DataSource ds;

    @BeforeEach
    void setUp() throws Exception {
        ds = FrozenDdl.newDatabase();
    }

    private static Customer customer(long n, String first) {
        return new Customer("CUST", "987654", n, "Mr", first, "Smith", new CobolDate(15, 6, 1980), "01962 815000",
                "1 High Street", "Hursley", "Winchester", "SO21 2JN", "United Kingdom", "ACTIVE",
                new CobolDate(1, 2, 2020), 450, new CobolDate(20, 2, 2020));
    }

    private static Account account(long cust, long n, String bal) {
        return new Account("ACCT", cust, "987654", n, "CURRENT", new BigDecimal("0.50"), new CobolDate(1, 2, 2020),
                500, new CobolDate(1, 9, 2026), new CobolDate(1, 10, 2026), new BigDecimal(bal), new BigDecimal(bal));
    }

    @Test
    void customerCrudMatchesCicsStatements() {
        JdbcCustomerRepository repo = new JdbcCustomerRepository(ds, "BANKZ");
        repo.insert(customer(1, "Andrew"));
        repo.insert(customer(7, "Zoe"));
        assertEquals(customer(1, "Andrew"), repo.find(new CustomerKey("987654", 1)).orElseThrow());
        assertEquals(7, repo.findHighest("987654").orElseThrow().customerNumber());
        assertEquals(1, repo.update(customer(1, "Andy")));
        assertEquals("Andy", repo.find(new CustomerKey("987654", 1)).orElseThrow().firstName());
        Customer stale = new Customer("CUST", "987654", 1, "Mr", "Andy", "Smith", new CobolDate(15, 6, 1980),
                "01962 815000", "2 Low Street", "Hursley", "Winchester", "SO21 2JN", "United Kingdom", "ACTIVE",
                new CobolDate(9, 9, 1999), 1, new CobolDate(9, 9, 1999));
        repo.update(stale);
        Customer after = repo.find(new CustomerKey("987654", 1)).orElseThrow();
        assertEquals("2 Low Street", after.addressLine1());
        assertEquals(450, after.creditScore(), "UPDCUST never changes the credit score");
        assertEquals(new CobolDate(20, 2, 2020), after.creditScoreReviewDate());
        assertEquals(new CobolDate(1, 2, 2020), after.created());
        assertEquals(1, repo.delete(new CustomerKey("987654", 1)));
        assertTrue(repo.find(new CustomerKey("987654", 1)).isEmpty());
        assertEquals(0, repo.delete(new CustomerKey("987654", 1)));
    }

    @Test
    void accountQueriesMatchInqaccAndInqacccu() {
        JdbcAccountRepository repo = new JdbcAccountRepository(ds, "BANKZ");
        for (int i = 1; i <= 25; i++) {
            repo.insert(account(1, i, i % 2 == 0 ? "-75.20" : "1234.56"));
        }
        repo.insert(account(2, 99, "1.00"));
        assertEquals(account(1, 2, "-75.20"), repo.find(new AccountKey("987654", 2)).orElseThrow());
        assertEquals(99, repo.findHighest("987654").orElseThrow().accountNumber());
        List<Account> mine = repo.findByCustomer(new CustomerKey("987654", 1), 20);
        assertEquals(20, mine.size());
        assertEquals(1, mine.get(0).accountNumber());
        AccountKey key = new AccountKey("987654", 2);
        assertEquals(1, repo.updateBalances(key, new BigDecimal("10.00"), new BigDecimal("12.00")));
        Account fresh = repo.find(key).orElseThrow();
        assertEquals(new BigDecimal("10.00"), fresh.availableBalance());
        assertEquals(new BigDecimal("12.00"), fresh.actualBalance());

        Account staleEdit = new Account("ACCT", 1, "987654", 2, "ISA", new BigDecimal("2.50"), new CobolDate(1, 2, 2020),
                900, new CobolDate(1, 1, 2000), new CobolDate(1, 1, 2000), new BigDecimal("-75.20"),
                new BigDecimal("-75.20"));
        assertEquals(1, repo.updateDetails(staleEdit));
        Account edited = repo.find(key).orElseThrow();
        assertEquals("ISA", edited.accountType());
        assertEquals(new BigDecimal("2.50"), edited.interestRate());
        assertEquals(900, edited.overdraftLimit());
        assertEquals(new BigDecimal("10.00"), edited.availableBalance(), "UPDACC must not touch balances");
        assertEquals(new CobolDate(1, 9, 2026), edited.lastStatementDate(), "UPDACC must not touch statement dates");
        assertEquals(1, repo.delete(key));
    }

    @Test
    void unitOfWorkRollsBackBalanceWhenAuditInsertFails() {
        new JdbcAccountRepository(ds, "BANKZ").insert(account(1, 10, "100.00"));
        AccountKey key = new AccountKey("987654", 10);
        ProcTran badAudit = new ProcTran("PRTR", "987654", 10, new CobolDate(18, 10, 2026), 111015, 1, "PCR",
                "x".repeat(41), new BigDecimal("50.00")); // PROCTRAN_DESC is CHAR(40)
        assertThrows(PersistenceException.class, () -> UnitOfWork.execute(ds, uow -> {
            JdbcAccountRepository accounts = new JdbcAccountRepository(uow, "BANKZ");
            assertEquals(new BigDecimal("100.00"), accounts.findForUpdate(key).orElseThrow().availableBalance());
            accounts.updateBalances(key, new BigDecimal("150.00"), new BigDecimal("150.00"));
            new JdbcProcTranRepository(uow, "BANKZ").insert(badAudit);
            return null;
        }));
        JdbcAccountRepository fresh = new JdbcAccountRepository(ds, "BANKZ");
        assertEquals(new BigDecimal("100.00"), fresh.find(key).orElseThrow().availableBalance());
        assertTrue(new JdbcProcTranRepository(ds, "BANKZ").findByAccount(key, 10).isEmpty());

        ProcTran goodAudit = new ProcTran("PRTR", "987654", 10, new CobolDate(18, 10, 2026), 111015, 1, "PCR",
                "Deposit", new BigDecimal("50.00"));
        UnitOfWork.execute(ds, uow -> {
            new JdbcAccountRepository(uow, "BANKZ").updateBalances(key, new BigDecimal("150.00"), new BigDecimal("150.00"));
            new JdbcProcTranRepository(uow, "BANKZ").insert(goodAudit);
            return null;
        });
        assertEquals(new BigDecimal("150.00"), fresh.find(key).orElseThrow().availableBalance());
        assertEquals(List.of(goodAudit), new JdbcProcTranRepository(ds, "BANKZ").findByAccount(key, 10));
    }

    @Test
    void schemaNamesAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> new JdbcCustomerRepository(ds, "BANKZ;DROP"));
    }

    @Test
    void procTranAuditRowsRoundTrip() {
        JdbcProcTranRepository repo = new JdbcProcTranRepository(ds, "BANKZ");
        ProcTran older = new ProcTran("PRTR", "987654", 10, new CobolDate(17, 10, 2026), 90000, 1, "PCR",
                "Deposit", new BigDecimal("250.00"));
        ProcTran newer = new ProcTran("PRTR", "987654", 10, new CobolDate(18, 10, 2026), 111015, 2, "PDR",
                "Withdrawal", new BigDecimal("-20.00"));
        repo.insert(older);
        repo.insert(newer);
        assertEquals(List.of(newer, older), repo.findByAccount(new AccountKey("987654", 10), 10));
    }
}
