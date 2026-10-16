package nazare.jmp.repository;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

/** In-memory H2 in Db2 compatibility mode, loaded with the BANKZ ACCOUNT and CUSTOMER DDL. */
final class H2Db2 {

	private H2Db2() {
	}

	static Connection open() throws SQLException {
		String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=DB2;DB_CLOSE_DELAY=-1"
				+ ";INIT=RUNSCRIPT FROM 'classpath:bankz-schema.sql'";
		Connection connection = DriverManager.getConnection(url);
		connection.setAutoCommit(false);
		return connection;
	}
}
