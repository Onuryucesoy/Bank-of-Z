# Bank-of-Z COBOL/PL/I → Java migration

This directory holds the **Phase 0 (contract freeze)** and **Phase 1 (data fidelity)** groundwork. No COBOL
business logic has been ported and no data has been migrated; nothing outside `migration/` was changed.

| Artifact | What it is |
|---|---|
| [operation-inventory.json](operation-inventory.json) / [.md](operation-inventory.md) | Every z/OS Connect asset: backend, COBOL program, CICS transid / IMS trancode, request/response schemas and COMMAREA layouts, OpenAPI operations mapped to it, Db2 tables, IMS PSB/PCBs, CICS semantics (HANDLE ABEND, SYNCPOINT, ABEND codes) |
| [copybook-field-mapping.json](copybook-field-mapping.json) / [.md](copybook-field-mapping.md) | Every copybook (85): field tree with byte offsets/sizes, PIC/usage, Java type, REDEFINES/OCCURS notes, 88-levels, Db2 DECLARE tables, and a cross-check of computed offsets against the z/OS Connect `.dai` files |
| [tools/generate_inventories.py](tools/generate_inventories.py) | Regenerates both inventories from the sources (`--check` fails when they are stale) |
| [java-service/](java-service) | Java 21 Gradle scaffold: JAX-RS stubs for all 17 OpenAPI operations, `CopybookCodec`, `CustomerIdRouter`, unit tests and the `goldenMaster` suite |
| [golden-master/](golden-master) | Synthetic fixtures for INQCUST, INQACC, INQACCS, INQTRAND, INQTRANL and the replay mechanism for captured z/OS Connect traffic |
| [tools/make_golden_fixtures.py](tools/make_golden_fixtures.py) | Regenerates the synthetic fixtures and the stub response file |

Verify with `cd migration/java-service && gradle build` (114 tests: 1 skipped — the replay test until recordings exist).

## Phased plan

| Phase | Goal | Exit criteria |
|---|---|---|
| **0 — Contract freeze** *(this change)* | Make `openapi.yaml` + the generated zosAsset schemas an executable spec | Inventory committed; every OpenAPI operation stubbed and schema-validated in CI; golden-master format agreed |
| **1 — Data fidelity** *(this change, foundation)* | Byte-exact COBOL ↔ Java conversion | Field mapping for every copybook; `CopybookCodec` round-trips zoned/packed/binary/EBCDIC with no `double`; layouts match `.dai` offsets |
| **2 — Shadow proxy, read ops** | Java serves INQCUST, INQACC, INQACCS, INQTRAND, INQTRANL against read-only Db2 replicas; a proxy sends each request to both z/OS Connect and Java, returns z/OS Connect's answer and logs diffs | Captured recordings replay with zero diffs for N days; latency within budget; then flip reads to Java with z/OS Connect as fallback |
| **3 — Write ops** | CRECUST, UPDCUST, DELCUS, DBCRFUN (CICS) behind the proxy | Transactional semantics proven equal (see risks R3); dual-run in a test region with reconciliation of CONTROL/PROCTRAN/ACCOUNT rows |
| **4 — IMS data consolidation** | Move IMS CUSTOMER/ACCOUNT/CUSTACCS/HISTORY into a relational model; port IBGCUDAT, IBSCUDAT, IBACSUM, IBTRAN | Hierarchical→relational mapping signed off (R2); IMS `/ims/*` responses reproduced, including array shapes (R5) |
| **5 — Decommission z/OS Connect** | Java owns all 17 operations | Proxy routes 100% to Java for a full business cycle; z/OS Connect APIs and the `src/api` deployment retired |

## Routing invariant (must hold in every phase)

From `validateCustomerId` / `parseCustomerId` in `src/frontend/js/utils.js`:

- `C` + one or more digits → **CICS / Db2** (`/customers/...`, `/accounts/...`); the digits after `C` are sent.
- `I` + **exactly 9** digits → **IMS** (`/ims/...`); the 9 digits are sent.
- Anything else is rejected (prefix is case-insensitive; surrounding whitespace is trimmed).

`java-service` implements this as `routing/CustomerIdRouter`, with tests copying the frontend's cases and error
messages. The shadow proxy and any future Java router must use it, so a given customer id can never be served by a
different backend than today. Note the OpenAPI contract itself does not encode the prefix — `/ims/customers/{customerId}`
receives the bare 9 digits — so the invariant lives in the client and must be preserved by convention.

## Data mapping rules (Phase 1)

- All `PIC 9` / `PIC S9` items — DISPLAY (zoned), `COMP`/`BINARY`, `COMP-3`/`PACKED-DECIMAL`, `COMP-5` — map to
  `java.math.BigDecimal` with the PIC scale. `double`/`float` are forbidden (`CopybookCodecTest` asserts the codec API
  has none) and encoding never rounds or truncates.
- `PIC X(n)` → fixed-length `String`, decoded/encoded with an explicit charset: **IBM037** (CCSID 037 is declared in
  every CICS `zosAsset.yaml`), padded with EBCDIC spaces (0x40).
- `PIC 9(8)` dates (and `9(6)` times) stay raw digit strings; the layout (`DDMMYYYY` in CUSTOMER/ACCOUNT copybooks,
  `YYYYMMDD` in PROCTRAN) is documented per field. No automatic `java.time` conversion.
- `REDEFINES` = overlay views of the same bytes (decode either, encode one); `OCCURS n` = fixed array (every slot
  serialised); `OCCURS m TO n DEPENDING ON c` = `List` sized by `c`.

## Risk register

| # | Risk | Where | Mitigation in place / planned |
|---|---|---|---|
| R1 | **COMP-3 / zoned / EBCDIC fidelity** — sign nibbles (C/D/F), overpunch zones, CP037 vs IBM-1047 code points (`[`, `]`, `¬`, `^` differ), rounding through `double` | Packed: IMS `BALANCE-AS PIC S9(13)V9(2) COMP-3` (IBACSUM/IBTRAN); zoned money: `PIC S9(10)V99` in ACCOUNT/PROCTRAN copybooks; OpenAPI declares money as `number/double` | `CopybookCodec` + byte-level tests; BigDecimal end-to-end; JSON diffs compare decimals; note `.cpy` files are stored as IBM-1047 in Git (`.gitattributes`) while runtime data is CCSID 037 |
| R2 | **IMS hierarchical → relational** — root segments CUSTOMER, ACCOUNT, CUSTACCS (non-unique `CUSTID,SEQ,M`), HISTORY plus lookup DBDs (ACCTYPE, CUSTTYPE, TSTAT, TSTATTYP, TTYPE); access is via `CALL 'CBLTDLI'` with PCB PROCOPTs | `src/base/ims/DBD/*.asm`, `src/base/ims/PSB/*.asm` (IBACSUM: ACCOUNT G, CUSTACCS G; IBTRAN: ACCOUNT R, CUSTACCS G, HISTORY I) | Inventory records PSB/PCBs per operation; Phase 4 to derive keys/FKs from DBD SEQ fields and replicate GU/GN ordering |
| R3 | **CICS transaction semantics** — `HANDLE ABEND`, `SYNCPOINT ROLLBACK`, `ABEND ABCODE(...)` decide what the caller sees and what is committed | `src/base/cics/cobol/INQCUST.cbl` (HANDLE ABEND l.202, SYNCPOINT ROLLBACK l.572, ABEND `CVR1`/`HROL`); also INQACC, INQACCS, INQACCCU, DBCRFUN; INQTRAND/INQTRANL abend `ITRD`/`ITRL` | Inventory lists per-program semantics; Phase 2 maps abends to the `abendCode` property / HTTP 500 exactly as `response_mapping.yaml` does; Phase 3 needs one Java transaction boundary per CICS UOW |
| R4 | **BMS dual-purpose programs** — terminal (3270) programs share copybooks/back-end programs with the API | `src/base/cics/cobol/BNK1CCS.cbl` (`SEND MAP('BNK1CCM')`, `RECEIVE MAP('BNK1CC')`, pseudo-conversational `RETURN TRANSID('OCCS')`), plus BNK1*/BNKMENU, CREACC, UPDACC | Out of scope for the API port; COBOL back ends stay authoritative until the 3270 UI is retired or re-pointed — no Db2 schema changes before then |
| R5 | **IMS array-shaped responses** — `/ims/accounts/{id}` maps `accountId` from `ACCOUNT-SUMMARY."ACCID-AS"` (an `OCCURS 1 TO 6` array), so z/OS Connect returns arrays where OpenAPI declares strings/numbers; the frontend copes | `src/frontend/account-details.html` (`Array.isArray` index matching), `src/frontend/js/api.js`; mapping in `src/api/src/main/operations/%2Fims%2Faccounts%2F%7BaccountId%7D/get/response_200.yaml` | Java stubs follow the **declared** OpenAPI shape; Phase 2/4 must decide (with frontend owners) whether to reproduce arrays byte-for-byte or fix the contract — recordings will show the real shape |
| R6 | **Contract drift already present** | IMS mapping emits `accountType: CHECKING` (not in the OpenAPI enum) and currency `USD` vs `GBP` for CICS; `ACCDB2.cpy` DECLARE says `DECIMAL(10,2)` balances / `DECIMAL(4,2)` rate while `.setup/jcl/cics/Db2-create.j2` creates `DECIMAL(12,2)` / `DECIMAL(6,2)` | Flagged in the inventories; golden-master recordings are the source of truth for behaviour, the DDL for storage |
| R7 | **Layout length mismatches in z/OS Connect metadata** | `.dai` record length 403 vs 399 bytes of fields (CRECUST); 1967 vs 1970 (INQACCS); all individual field offsets match | `daiCrossCheck` in `copybook-field-mapping.json`; confirm against the deployed COMMAREA before byte-level adapters |
| R8 | **Fixed-size arrays** | INQTRANL returns `OCCURS 100` transactions (14 551-byte COMMAREA) and filters blanks in the mapping; INQACCS/INQACCCU `OCCURS 1 TO 20 DEPENDING ON` | Fixtures keep all 100 slots; Java adapters must apply the same blank-slot filtering as `response_mapping.yaml` |

## Assumptions checked

| Assumption | Result |
|---|---|
| OpenAPI at `src/api/src/main/api/openapi.yaml` | Held (OpenAPI 3.0.3, base path `/api`, 17 operations on 14 paths) |
| `zosAssets/` location | **Differs**: `src/api/src/main/zosAssets/` (not repo root, not `src/api/zosAssets`) |
| ~15 operations | **14** asset directories — exactly the 14 listed; no others. All 17 OpenAPI operations map to one of them (INQACC and IBACSUM back several) |
| `providerFiles/gen/requestSchema.json` / `responseSchema.json` | Held for all 14 (JSON Schema draft-04 with `x-ibm-zcon-metadata`) |
| CICS copybooks ~38 | **42** in `src/base/cics/copy/` (RESPSTR and WAZI declare no data items) |
| IMS copybooks ~4 | Held: IBGHIST, IBSHIST, IBTRAN, JNI |
| Generated copybooks under `providerFiles/gen/` | Held (36), plus 3 provider copies directly in `providerFiles/` |
| IMS = `IB*` operations | Held: IBACSUM, IBGCUDAT, IBSCUDAT, IBTRAN (`imsTransaction-1.0`); IMS request/response layouts come from the `.cbl` source (and `IBTRAN.cpy`), not `src/base/ims/copy` alone |
| One COBOL program per asset | Held: `src/base/cics/cobol/<ASSET>.cbl` or `src/base/ims/cobol/<ASSET>.cbl`; z/OS Connect uses the API copybooks `*Z.cpy` (e.g. `INQCUSTZ.cpy`) |
| Repo has a Gradle multi-project / `gradlew` | **Differs**: no root build and no wrapper; `src/api` and `src/base/ims/java` are independent Gradle builds, so `migration/java-service` is a third standalone build (installed Gradle 8.10.2) |
| CICS transids | Mostly `OMEN`; INQTRAND `ITRD`, INQTRANL `ITRL`; UPDCUST's `zosAsset.yaml` has no transid and `ccsid: 37` |
