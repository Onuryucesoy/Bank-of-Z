package com.ibm.bankofz.core.persistence;

import com.ibm.bankofz.core.mapper.AccountRowMapper;
import com.ibm.bankofz.core.model.Account;
import com.ibm.bankofz.core.model.AccountKey;
import com.ibm.bankofz.core.model.AccountRow;
import com.ibm.bankofz.core.model.CustomerKey;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;

/** JDBC (Db2 JCC) implementation of {@link AccountRepository} over {@code <schema>.ACCOUNT}. */
public final class JdbcAccountRepository implements AccountRepository {

    private static final String COLUMNS = "ACCOUNT_EYECATCHER, ACCOUNT_CUSTOMER_NUMBER, ACCOUNT_SORTCODE, "
            + "ACCOUNT_NUMBER, ACCOUNT_TYPE, ACCOUNT_INTEREST_RATE, ACCOUNT_OPENED, ACCOUNT_OVERDRAFT_LIMIT, "
            + "ACCOUNT_LAST_STATEMENT, ACCOUNT_NEXT_STATEMENT, ACCOUNT_AVAILABLE_BALANCE, ACCOUNT_ACTUAL_BALANCE";

    private final DataSource dataSource;
    private final String table;

    public JdbcAccountRepository(DataSource dataSource, String schema) {
        this.dataSource = dataSource;
        this.table = Sql.requireIdentifier(schema) + ".ACCOUNT";
    }

    @Override
    public Optional<Account> find(AccountKey key) {
        List<Account> l = query("SELECT " + COLUMNS + " FROM " + table
                + " WHERE ACCOUNT_SORTCODE = ? AND ACCOUNT_NUMBER = ?", key.sortCode(), key.accountNumberText());
        return l.stream().findFirst();
    }

    @Override
    public Optional<Account> findHighest(String sortCode) {
        return query("SELECT " + COLUMNS + " FROM " + table
                + " WHERE ACCOUNT_SORTCODE = ? ORDER BY ACCOUNT_NUMBER DESC FETCH FIRST 1 ROWS ONLY", sortCode)
                .stream().findFirst();
    }

    @Override
    public List<Account> findByCustomer(CustomerKey customer, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return query("SELECT " + COLUMNS + " FROM " + table
                + " WHERE ACCOUNT_CUSTOMER_NUMBER = ? AND ACCOUNT_SORTCODE = ? ORDER BY ACCOUNT_NUMBER"
                + " FETCH FIRST " + limit + " ROWS ONLY", customer.customerNumberText(), customer.sortCode());
    }

    @Override
    public void insert(Account account) {
        AccountRow r = AccountRowMapper.toRow(account);
        execute("INSERT INTO " + table + " (" + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                r.eyecatcher(), r.customerNumber(), r.sortCode(), r.accountNumber(), r.accountType(),
                r.interestRate(), Sql.date(r.opened()), r.overdraftLimit(), Sql.date(r.lastStatement()),
                Sql.date(r.nextStatement()), r.availableBalance(), r.actualBalance());
    }

    @Override
    public int update(Account account) {
        AccountRow r = AccountRowMapper.toRow(account);
        return execute("UPDATE " + table + " SET ACCOUNT_TYPE = ?, ACCOUNT_INTEREST_RATE = ?, "
                + "ACCOUNT_OVERDRAFT_LIMIT = ?, ACCOUNT_LAST_STATEMENT = ?, ACCOUNT_NEXT_STATEMENT = ?, "
                + "ACCOUNT_AVAILABLE_BALANCE = ?, ACCOUNT_ACTUAL_BALANCE = ? "
                + "WHERE ACCOUNT_SORTCODE = ? AND ACCOUNT_NUMBER = ?",
                r.accountType(), r.interestRate(), r.overdraftLimit(), Sql.date(r.lastStatement()),
                Sql.date(r.nextStatement()), r.availableBalance(), r.actualBalance(), r.sortCode(),
                r.accountNumber());
    }

    @Override
    public int delete(AccountKey key) {
        return execute("DELETE FROM " + table + " WHERE ACCOUNT_SORTCODE = ? AND ACCOUNT_NUMBER = ?",
                key.sortCode(), key.accountNumberText());
    }

    private List<Account> query(String sql, Object... args) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            JdbcCustomerRepository.bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                List<Account> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(AccountRowMapper.fromRow(new AccountRow(Sql.chr(rs, "ACCOUNT_EYECATCHER"),
                            Sql.chr(rs, "ACCOUNT_CUSTOMER_NUMBER"), Sql.chr(rs, "ACCOUNT_SORTCODE"),
                            Sql.chr(rs, "ACCOUNT_NUMBER"), Sql.chr(rs, "ACCOUNT_TYPE"),
                            rs.getBigDecimal("ACCOUNT_INTEREST_RATE"), Sql.date(rs, "ACCOUNT_OPENED"),
                            Sql.intOrNull(rs, "ACCOUNT_OVERDRAFT_LIMIT"), Sql.date(rs, "ACCOUNT_LAST_STATEMENT"),
                            Sql.date(rs, "ACCOUNT_NEXT_STATEMENT"), rs.getBigDecimal("ACCOUNT_AVAILABLE_BALANCE"),
                            rs.getBigDecimal("ACCOUNT_ACTUAL_BALANCE"))));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new PersistenceException(sql, e);
        }
    }

    private int execute(String sql, Object... args) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            JdbcCustomerRepository.bind(ps, args);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new PersistenceException(sql, e);
        }
    }
}
