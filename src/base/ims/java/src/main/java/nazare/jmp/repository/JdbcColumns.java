package nazare.jmp.repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.regex.Pattern;

import nazare.jmp.model.SortCode;

/** Conversions between Java values and the Db2 column encodings used by the COBOL programs. */
final class JdbcColumns {

	private static final Pattern SCHEMA_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_#$@]{0,127}");

	private JdbcColumns() {
	}

	static String qualify(String schema, String table) {
		if (schema == null || schema.isBlank()) {
			return table;
		}
		if (!SCHEMA_NAME.matcher(schema).matches()) {
			throw new IllegalArgumentException("Invalid schema name: " + schema);
		}
		return schema + "." + table;
	}

	/** CHAR columns come back blank-padded from Db2; COBOL never stores trailing spaces meaningfully. */
	static String getChar(ResultSet rs, String column) throws SQLException {
		String value = rs.getString(column);
		return value == null ? null : value.stripTrailing();
	}

	static void setChar(PreparedStatement ps, int index, String value, int length, String column)
			throws SQLException {
		if (value != null && value.length() > length) {
			throw new IllegalArgumentException(column + " exceeds CHAR(" + length + "): '" + value + "'");
		}
		ps.setString(index, value);
	}

	/** Numeric keys (PIC 9(n)) are stored in CHAR(n) columns as zero-padded digits. */
	static String formatNumber(long value, int length, String column) {
		if (value < 0 || String.valueOf(value).length() > length) {
			throw new IllegalArgumentException(column + " must be 0-" + "9".repeat(length) + ": " + value);
		}
		return String.format("%0" + length + "d", value);
	}

	static long parseNumber(ResultSet rs, String column) throws SQLException {
		String value = rs.getString(column);
		if (value == null || value.isBlank()) {
			return 0L;
		}
		try {
			return Long.parseLong(value.strip());
		} catch (NumberFormatException e) {
			throw new SQLException(column + " is not numeric: '" + value + "'", e);
		}
	}

	static String requireSortcode(String sortcode) {
		if (!SortCode.isValid(sortcode)) {
			throw new IllegalArgumentException("Sort code must be 6 digits: " + sortcode);
		}
		return sortcode;
	}

	/** Customer dates are INTEGER YYYYMMDD (see CRECUST / INQCUST); 0 or NULL means no date. */
	static LocalDate getYyyymmdd(ResultSet rs, String column) throws SQLException {
		int value = rs.getInt(column);
		if (rs.wasNull() || value == 0) {
			return null;
		}
		try {
			return LocalDate.of(value / 10000, (value / 100) % 100, value % 100);
		} catch (DateTimeException e) {
			throw new SQLException(column + " is not a valid YYYYMMDD date: " + value, e);
		}
	}

	static void setYyyymmdd(PreparedStatement ps, int index, LocalDate date) throws SQLException {
		if (date == null) {
			ps.setNull(index, Types.INTEGER);
		} else {
			if (date.getYear() < 1 || date.getYear() > 9999) {
				throw new IllegalArgumentException("Year must be 0001-9999: " + date);
			}
			ps.setInt(index, date.getYear() * 10000 + date.getMonthValue() * 100 + date.getDayOfMonth());
		}
	}

	static LocalDate getDate(ResultSet rs, String column) throws SQLException {
		Date value = rs.getDate(column);
		return value == null ? null : value.toLocalDate();
	}

	static void setDate(PreparedStatement ps, int index, LocalDate date) throws SQLException {
		if (date == null) {
			ps.setNull(index, Types.DATE);
		} else {
			ps.setDate(index, Date.valueOf(date));
		}
	}

	static Integer getInteger(ResultSet rs, String column) throws SQLException {
		int value = rs.getInt(column);
		return rs.wasNull() ? null : value;
	}

	static void setInteger(PreparedStatement ps, int index, Integer value, int sqlType) throws SQLException {
		if (value == null) {
			ps.setNull(index, sqlType);
		} else {
			ps.setInt(index, value);
		}
	}

	/**
	 * Rejects values Db2 would otherwise silently truncate: more than 2 decimal places, or more integer
	 * digits than {@code DECIMAL(precision, 2)} allows.
	 */
	static void setDecimal(PreparedStatement ps, int index, BigDecimal value, int precision, String column)
			throws SQLException {
		if (value == null) {
			ps.setNull(index, Types.DECIMAL);
			return;
		}
		BigDecimal scaled;
		try {
			scaled = value.setScale(2);
		} catch (ArithmeticException e) {
			throw new IllegalArgumentException(column + " must have at most 2 decimal places: " + value, e);
		}
		if (scaled.precision() > precision) {
			throw new IllegalArgumentException(column + " exceeds DECIMAL(" + precision + ",2): " + value);
		}
		ps.setBigDecimal(index, scaled);
	}
}
