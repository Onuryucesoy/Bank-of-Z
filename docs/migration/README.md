# Migration: Phase 0 and Phase 1 deliverables

This folder holds the characterization documents for moving Bank of Z (CICS COBOL + IMS COBOL/PL/I + Db2) to Java one program at a time, behind the existing z/OS Connect APIs.

| Document | What it is for |
|---|---|
| [inventory.md](inventory.md) | Every source artifact under `src/base/`, who calls it, and the hidden behaviour found while reading it |
| [data-contracts.md](data-contracts.md) | Byte-level data semantics (zoned and packed decimal, binary, EBCDIC, IMS segments) a Java port must reproduce |
| [edge-case-checklist.md](edge-case-checklist.md) | Sign-off checklist to complete for each migrated program |

The Phase 1 Java code lives in the existing IMS Java module, `src/base/ims/java`:

| Package | Contents |
|---|---|
| `nazare.jmp.cobol` | `Money`, `PicSpec`, `CobolRounding`, `PackedDecimal` (COMP-3), `ZonedDecimal` (DISPLAY), `Ebcdic` (Cp1047), `CobolDate` |
| `nazare.jmp.commarea` | `ProcIsrtCommarea` (one record per function code), `FunctionCode`, `ProcIsrtCodec` (131-byte EBCDIC image) |
| `nazare.jmp.experimental.ims` | Read-only CUSTOMER / CUSTACCS / ACCOUNT reader over the IMS Universal JDBC driver, plus `AccountSummaryQuery` (IBACSUM) and `LoginCheck` (IBLOGIN without the REPL). Disabled unless `-Dnazare.experimental.ims.readonly=true` |

Run the tests with `cd src/base/ims/java && gradle test`. The z/OS build (`gradle clean jar`) does not compile or package the tests.

Nothing in Phase 1 changes production behaviour. No COBOL, PL/I, `src/api`, or `src/frontend` file is modified. The JMP controllers don't reference the new classes, and no IMS transaction or PSB points at them.

Not shown in site navigation: Jekyll only lists pages under `docs/docs/`, so these Markdown files are read on GitHub.
