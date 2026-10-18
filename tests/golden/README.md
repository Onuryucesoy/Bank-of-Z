# Golden-master fixtures (Phase 0)

Baseline request/response pairs for the CICS transactions that the Java
service will replace. They are the reference the Phase 2+ shadow-mode
comparison checks Java output against.

> **Status: `HAND_AUTHORED_UNVALIDATED`.** COBOL cannot run in CI, so every
> fixture was written by hand from the copybook layout, the z/OS Connect
> mappings and a reading of the COBOL source. Each must be re-captured
> from (or confirmed against) the live CICS region before it is used to
> sign off a ported transaction. When a fixture is confirmed, set
> `baseline.status` to `CAPTURED` and record the capture in `baseline.note`.

| Fixture | Program | Interface | Layout |
|---|---|---|---|
| `INQCUST.json` | `INQCUST` | COMMAREA | `src/base/cics/copy/INQCUSTZ.cpy` |
| `INQACC.json` | `INQACC` | COMMAREA | `src/base/cics/copy/INQACC.cpy` |
| `CRECUST.json` | `CRECUST` | COMMAREA | `src/base/cics/copy/CRECUST.cpy` |
| `DELCUS.json` | `DELCUS` | COMMAREA | `src/base/cics/copy/DELCUS.cpy` |
| `XFRFUN.json` | `XFRFUN` | COMMAREA | `src/base/cics/copy/XFRFUN.cpy` |
| `DBCRFUN.json` | `DBCRFUN` | COMMAREA | `src/base/cics/copy/PAYDBCR.cpy` |
| `UPDCUST.json` | `UPDCUST` | COMMAREA | `src/base/cics/copy/UPDCUST.cpy` |
| `CRDTAGY1..5.json` | `CRDTAGY1..5` | Container `CIPA..CIPE` on channel `CIPCREDCHANN`, transid `OCR1..5` | `src/base/cics/copy/CUSTOMER.cpy` (`WS-CONT-IN`) |

## Format

```jsonc
{
  "transaction": "INQACC",                       // file name must match
  "program": "src/base/cics/cobol/INQACC.cbl",
  "interface": "CICS_COMMAREA",                  // or CICS_CONTAINER
  "copybook": "src/base/cics/copy/INQACC.cpy",   // frozen layout
  "record": "INQACC-COMMAREA",                   // 01-level within the copybook
  "ccsid": 37,                                   // EBCDIC code page of the bytes
  "channel": "...", "container": "...", "transid": "...",   // containers only
  "baseline": { "status": "HAND_AUTHORED_UNVALIDATED", "note": "...", "derivedFrom": ["..."] },
  "cases": [{
    "name": "found",
    "description": "...",
    "request":  { "INQACC-ACCNO": "10" },        // elementary COBOL names
    "response": { "INQACC-AVAIL-BAL": "-75.20", "INQACC-SUCCESS": "Y" },
    "nonDeterministic": ["..."]                  // optional: checked for presence only
  }]
}
```

- Keys are elementary data names from the copybook (`NAME OF PARENT` if
  ambiguous, `NAME(n)` for OCCURS). Unknown names fail the build.
- Values are strings: text for `PIC X`, plain decimals for `PIC 9`
  (`"-75.20"`, never JSON numbers, so no binary floating point is involved).
- Any field not listed holds its `INITIALIZE` default (spaces / zero) and is
  compared as such.
- Over-long text and numbers that do not fit their PIC are rejected rather
  than truncated.

## Harness

`src/java-service` → `GoldenFixtureRoundTripTest` (run with `gradle test`):

1. Every inventoried transaction has a fixture.
2. Each request/response encodes to exactly the copybook length in CP037,
   decodes back to the same field values, and re-encodes to identical bytes.
3. `GoldenMasterComparator` reports differences per field (with offset)
   and per byte; Phase 2 points it at Java service output instead of the
   re-encoded fixture, and at bytes captured from CICS once available.

Non-deterministic fields (allocated customer number, random credit scores,
dates relative to "today") are listed per case; the comparator checks they
are present and valid for their PIC but not their value.
