package com.ibm.bankofz.core.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        Account updated = account(1, 2, "10.00");
        assertEquals(1, repo.update(updated));
        assertEquals(new BigDecimal("10.00"), repo.find(updated.key()).orElseThrow().availableBalance());
        assertEquals(1, repo.delete(updated.key()));
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
