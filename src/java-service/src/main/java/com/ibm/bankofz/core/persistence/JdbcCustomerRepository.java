package com.ibm.bankofz.core.persistence;

import com.ibm.bankofz.core.mapper.CustomerRowMapper;
import com.ibm.bankofz.core.model.Customer;
import com.ibm.bankofz.core.model.CustomerKey;
import com.ibm.bankofz.core.model.CustomerRow;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import javax.sql.DataSource;

/** JDBC (Db2 JCC) implementation of {@link CustomerRepository} over {@code <schema>.CUSTOMER}. */
public final class JdbcCustomerRepository implements CustomerRepository {

    private static final String COLUMNS = "CUSTOMER_EYECATCHER, CUSTOMER_SORTCODE, CUSTOMER_NUMBER, CUSTOMER_TITLE, "
            + "CUSTOMER_FIRST_NAME, CUSTOMER_LAST_NAME, CUSTOMER_DATE_OF_BIRTH, CUSTOMER_PHONE, "
            + "CUSTOMER_ADDR_LINE1, CUSTOMER_ADDR_LINE2, CUSTOMER_CITY, CUSTOMER_POSTCODE, CUSTOMER_COUNTRY, "
            + "CUSTOMER_STATUS, CUSTOMER_CREATED_DATE, CUSTOMER_CREDIT_SCORE, CUSTOMER_CS_REVIEW_DATE";

    private final DataSource dataSource;
    private final String table;

    public JdbcCustomerRepository(DataSource dataSource, String schema) {
        this.dataSource = dataSource;
        this.table = Sql.requireIdentifier(schema) + ".CUSTOMER";
    }

    @Override
    public Optional<Customer> find(CustomerKey key) {
        return queryOne("SELECT " + COLUMNS + " FROM " + table
                + " WHERE CUSTOMER_SORTCODE = ? AND CUSTOMER_NUMBER = ?", key.sortCode(), key.customerNumberText());
    }

    @Override
    public Optional<Customer> findHighest(String sortCode) {
        return queryOne("SELECT " + COLUMNS + " FROM " + table
                + " WHERE CUSTOMER_SORTCODE = ? ORDER BY CUSTOMER_NUMBER DESC FETCH FIRST 1 ROW ONLY", sortCode);
    }

    @Override
    public void insert(Customer customer) {
        CustomerRow r = CustomerRowMapper.toRow(customer);
        execute("INSERT INTO " + table + " (" + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                r.eyecatcher(), r.sortCode(), r.customerNumber(), r.title(), r.firstName(), r.lastName(),
                r.dateOfBirth(), r.phone(), r.addressLine1(), r.addressLine2(), r.city(), r.postcode(),
                r.country(), r.status(), r.createdDate(), r.creditScore(), r.creditScoreReviewDate());
    }

    @Override
    public int update(Customer customer) {
        CustomerRow r = CustomerRowMapper.toRow(customer);
        return execute("UPDATE " + table + " SET CUSTOMER_TITLE = ?, CUSTOMER_FIRST_NAME = ?, "
                + "CUSTOMER_LAST_NAME = ?, CUSTOMER_DATE_OF_BIRTH = ?, CUSTOMER_PHONE = ?, CUSTOMER_ADDR_LINE1 = ?, "
                + "CUSTOMER_ADDR_LINE2 = ?, CUSTOMER_CITY = ?, CUSTOMER_POSTCODE = ?, CUSTOMER_COUNTRY = ?, "
                + "CUSTOMER_STATUS = ?, CUSTOMER_CREDIT_SCORE = ?, CUSTOMER_CS_REVIEW_DATE = ? "
                + "WHERE CUSTOMER_SORTCODE = ? AND CUSTOMER_NUMBER = ?",
                r.title(), r.firstName(), r.lastName(), r.dateOfBirth(), r.phone(), r.addressLine1(),
                r.addressLine2(), r.city(), r.postcode(), r.country(), r.status(), r.creditScore(),
                r.creditScoreReviewDate(), r.sortCode(), r.customerNumber());
    }

    @Override
    public int delete(CustomerKey key) {
        return execute("DELETE FROM " + table + " WHERE CUSTOMER_SORTCODE = ? AND CUSTOMER_NUMBER = ?",
                key.sortCode(), key.customerNumberText());
    }

    private Optional<Customer> queryOne(String sql, Object... args) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(CustomerRowMapper.fromRow(row(rs))) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new PersistenceException(sql, e);
        }
    }

    private int execute(String sql, Object... args) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, args);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new PersistenceException(sql, e);
        }
    }

    static void bind(PreparedStatement ps, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            ps.setObject(i + 1, args[i]);
        }
    }

    private static CustomerRow row(ResultSet rs) throws SQLException {
        int score = rs.getShort("CUSTOMER_CREDIT_SCORE");
        Short creditScore = rs.wasNull() ? null : (short) score;
        return new CustomerRow(Sql.chr(rs, "CUSTOMER_EYECATCHER"), Sql.chr(rs, "CUSTOMER_SORTCODE"),
                Sql.chr(rs, "CUSTOMER_NUMBER"), Sql.chr(rs, "CUSTOMER_TITLE"), Sql.chr(rs, "CUSTOMER_FIRST_NAME"),
                Sql.chr(rs, "CUSTOMER_LAST_NAME"), Sql.intOrNull(rs, "CUSTOMER_DATE_OF_BIRTH"),
                Sql.chr(rs, "CUSTOMER_PHONE"), Sql.chr(rs, "CUSTOMER_ADDR_LINE1"), Sql.chr(rs, "CUSTOMER_ADDR_LINE2"),
                Sql.chr(rs, "CUSTOMER_CITY"), Sql.chr(rs, "CUSTOMER_POSTCODE"), Sql.chr(rs, "CUSTOMER_COUNTRY"),
                Sql.chr(rs, "CUSTOMER_STATUS"), Sql.intOrNull(rs, "CUSTOMER_CREATED_DATE"), creditScore,
                Sql.intOrNull(rs, "CUSTOMER_CS_REVIEW_DATE"));
    }
}
