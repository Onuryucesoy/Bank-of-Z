package com.ibm.bankofz.core.persistence;

import com.ibm.bankofz.core.mapper.ProcTranRowMapper;
import com.ibm.bankofz.core.model.AccountKey;
import com.ibm.bankofz.core.model.ProcTran;
import com.ibm.bankofz.core.model.ProcTranRow;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

/** JDBC (Db2 JCC) implementation of {@link ProcTranRepository} over {@code <schema>.PROCTRAN}. */
public final class JdbcProcTranRepository implements ProcTranRepository {

    private static final String COLUMNS = "PROCTRAN_EYECATCHER, PROCTRAN_SORTCODE, PROCTRAN_NUMBER, PROCTRAN_DATE, "
            + "PROCTRAN_TIME, PROCTRAN_REF, PROCTRAN_TYPE, PROCTRAN_DESC, PROCTRAN_AMOUNT";

    private final Connections connections;
    private final String table;

    /** Auto-commit per statement; use the {@link Connections} constructor to join a {@link UnitOfWork}. */
    public JdbcProcTranRepository(DataSource dataSource, String schema) {
        this(Connections.autoCommit(dataSource), schema);
    }

    public JdbcProcTranRepository(Connections connections, String schema) {
        this.connections = connections;
        this.table = Sql.requireIdentifier(schema) + ".PROCTRAN";
    }

    @Override
    public void insert(ProcTran entry) {
        ProcTranRow r = ProcTranRowMapper.toRow(entry);
        String sql = "INSERT INTO " + table + " (" + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?)";
        try (Connections.Lease l = connections.lease(); PreparedStatement ps = l.connection().prepareStatement(sql)) {
            JdbcCustomerRepository.bind(ps, r.eyecatcher(), r.sortCode(), r.accountNumber(), Sql.date(r.date()),
                    r.time(), r.reference(), r.type(), r.description(), r.amount());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new PersistenceException(sql, e);
        }
    }

    @Override
    public List<ProcTran> findByAccount(AccountKey account, int limit) {
        String sql = "SELECT " + COLUMNS + " FROM " + table + " WHERE PROCTRAN_SORTCODE = ? AND PROCTRAN_NUMBER = ?"
                + " ORDER BY PROCTRAN_DATE DESC, PROCTRAN_TIME DESC FETCH FIRST " + Math.max(1, limit) + " ROWS ONLY";
        try (Connections.Lease l = connections.lease(); PreparedStatement ps = l.connection().prepareStatement(sql)) {
            JdbcCustomerRepository.bind(ps, account.sortCode(), account.accountNumberText());
            try (ResultSet rs = ps.executeQuery()) {
                List<ProcTran> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(ProcTranRowMapper.fromRow(new ProcTranRow(Sql.chr(rs, "PROCTRAN_EYECATCHER"),
                            Sql.chr(rs, "PROCTRAN_SORTCODE"), Sql.chr(rs, "PROCTRAN_NUMBER"),
                            Sql.date(rs, "PROCTRAN_DATE"), Sql.chr(rs, "PROCTRAN_TIME"), Sql.chr(rs, "PROCTRAN_REF"),
                            Sql.chr(rs, "PROCTRAN_TYPE"), Sql.chr(rs, "PROCTRAN_DESC"),
                            rs.getBigDecimal("PROCTRAN_AMOUNT"))));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new PersistenceException(sql, e);
        }
    }
}
