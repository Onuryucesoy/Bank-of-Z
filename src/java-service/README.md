# bankofz-java-service (migration Phase 1 foundation)

Java 21 / Gradle library that later phases build the strangler-fig Java
service on. It contains **no ported transaction logic**.

| Package | Contents |
|---|---|
| `codec` | CP037 text, zoned decimal (overpunch and separate sign), COMP-3 packed decimal, COMP/COMP-5 binary. The only place bytes cross the EBCDIC boundary. |
| `copybook` | Fixed-format copybook parser and layout-driven `RecordCodec` (used by the golden-master harness and to generate `docs/migration/data-contracts.md`). |
| `model` | Records for `CUSTOMER`, `ACCOUNT`, `PROCTRAN` and Db2 rows `CUSTDB2`/`ACCDB2`/`PROCDB2`; `CobolDate`; keys. |
| `mapper` | Hand-written byte codecs for the core records (cross-checked against the parser) and record ↔ Db2 row mappers. |
| `persistence` | Repository interfaces + plain JDBC implementations for `BANKZ.CUSTOMER/ACCOUNT/PROCTRAN`; `UnitOfWork` shares one connection/transaction across repositories (writes + PROCTRAN audit). |
| `id` | `IdAllocator` replacing the `BANKZCUST`/`BANKZACCT` counters: `ControlTableIdAllocator` (coexistence), `SequenceIdAllocator` (post-cutover, created from the CONTROL values by `createFromControl`), `InMemoryIdAllocator`. |
| `service` | Interfaces for the inventoried transactions (INQCUST, INQACC, INQACCCU, CRECUST, UPDCUST, DELCUS, CREACC, UPDACC, DELACC, DBCRFUN, XFRFUN, CRDTAGY1-5, IBGCUDAT, IBACSUM) and `CustomerRouting`. |
| `golden` | `GoldenMasterComparator`. |

Build and test (no mainframe needed; tests use H2 in DB2 mode loaded with the
CREATE TABLE statements from `.setup/jcl/cics/Db2-create.j2`):

```sh
cd src/java-service
gradle test
```

Dependencies match `src/base/ims/java`: `com.ibm.db2:jcc:12.1.5.0` (runtime).
JZOS is not needed because all EBCDIC handling uses the JDK `IBM037` charset.
No framework is chosen yet (the existing Java component is a plain IMS JMP
module); Phase 2 adds the HTTP layer on top of these interfaces.
