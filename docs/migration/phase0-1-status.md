# Migration status: Phase 0 and Phase 1

**Scope:** contract freeze and Java foundation only. No COBOL, PL/I,
Assembler, BMS, JCL, z/OS Connect or `.setup/` file was changed, and no
transaction logic was ported. All new code is in new directories.

## Built

### Phase 0: contract inventory and freeze

| Artefact | Contents |
|---|---|
| [data-contracts.md](data-contracts.md) | Field-level layout (level, offset, length, PIC, usage, Java type, encoding notes, consumers) for 47 copybooks/records, the 4 Db2 tables, type-mapping rules and the copybook/DDL discrepancies found. Generated from the copybooks by the Java parser, so offsets are computed rather than typed by hand. |
| [api-contract.md](api-contract.md) | 17 operations on 14 paths: z/OS asset, backend program/transid, request/response schemas, and every JSON ↔ copybook-field mapping and status rule, extracted from `src/api/src/main/operations/`. |
| [invariants.md](invariants.md) | BANKZCUST allocation, the CRECUST → OCR1-5/CRDTAGY1-5 fan-out, the DELCUS cascade, PROCTRAN audit, CICS/IMS routing, and per-program edge cases. |
| `tests/golden/` | 12 fixture files (INQCUST, INQACC, CRECUST, DELCUS, XFRFUN, DBCRFUN, UPDCUST, CRDTAGY1-5), 22 cases, all marked `HAND_AUTHORED_UNVALIDATED`. Format and validation process are in `tests/golden/README.md`. |

### Phase 1: Java foundation (`src/java-service`)

- Gradle module on Java 21, same stack as `src/base/ims/java` (Db2 JCC
  12.1.5.0). JZOS is not needed because CP037 comes from the JDK.
- EBCDIC boundary codecs (`codec`): CP037 text, zoned decimal (overpunch
  and separate sign), COMP-3 packed, COMP/COMP-5 binary. Values that would
  be truncated are rejected rather than silently cut.
- A copybook parser and `RecordCodec` (`copybook`) that parse every
  transaction copybook in the repo.
- Models (`model`) for CUSTOMER, ACCOUNT and PROCTRAN, plus CUSTDB2,
  ACCDB2 and PROCDB2 rows. Hand-written byte codecs and row mappers
  (`mapper`) are tested to produce exactly the bytes that the
  copybook-driven codec produces.
- Repositories (`persistence`) for CUSTOMER, ACCOUNT and PROCTRAN, one
  method per SQL statement the CICS programs issue.
- ID allocation (`id`): an `IdAllocator` interface with three
  implementations: CONTROL-row (coexistence), Db2 SEQUENCE (post-cutover)
  and in-memory.
- Service interfaces (`service`) for the inventoried transactions, plus
  `CustomerRouting`. There are no implementations yet.
- `GoldenMasterComparator` for field-level and byte-level diffs.

### Verification

`cd src/java-service && gradle test` compiles with `-Xlint:all -Werror`
and passes all tests (codecs, parser over all copybooks, fixture round
trip, model/copybook byte equality, H2 (DB2 mode) repositories and
allocators built from the frozen DDL, routing). CI does not run these
tests yet: the existing workflows are self-hosted z/OS jobs.

**Not verified:** the module was not run against a real Db2 for z/OS or a
CICS region. It is not wired into the DBB build or `.setup/` deployment.

## Remaining for Phase 2: read-only port, shadow mode

Ports `INQCUST`, `INQACC`, `IBACSUM` and `IBGCUDAT` (plus `INQACCCU`,
which `GET /customers/{id}/accounts` needs).

1. **Capture real baselines.** Record COMMAREAs and responses from the live
   region for each fixture case and replace the hand-authored values. Set
   `baseline.status: CAPTURED`. Add IMS fixtures (IBACSUM, IBGCUDAT) using
   the gen `INPUT-AREA`/`OUTPUT-AREA` layouts, which the parser already
   reads.
2. **Implement** `CustomerQueryService` and `AccountQueryService` on the
   JDBC repositories, keeping the special keys (customer 0 = random, all 9s
   = highest), the INQACC "not found is still success" behaviour, and the
   20-account cap.
3. **IMS reads:** implement `ImsCustomerQueryService` against IMS DB (IMS
   Universal JDBC driver, as `src/base/ims/java` does) or through the
   existing IMS transactions.
4. **HTTP layer:** pick the runtime (Liberty JAX-RS or Spring Boot). Serve
   the same OpenAPI routes and shapes as `api-contract.md`, reusing the
   z/OS Connect mapping expressions as the specification.
5. **Shadow mode:** send read traffic to both paths, compare with
   `GoldenMasterComparator`, and log mismatches. COBOL stays the system of
   record.
6. **CI:** add a GitHub-hosted job running `gradle test` in
   `src/java-service`.

Write paths (CRECUST, UPDCUST, DELCUS, DBCRFUN, XFRFUN, CREACC/UPDACC/DELACC)
and the switch of ID-allocation ownership follow in later phases. The
coexistence rule in [invariants.md §1](invariants.md#1-bankzcust-customer-number-allocation)
applies.
