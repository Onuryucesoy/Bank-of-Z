# Data contracts

Byte-level semantics a Java implementation has to reproduce. Every rule below is backed by a test in `src/base/ims/java/src/test/java/nazare/jmp/cobol` or `.../commarea`.

## 1. Numeric usages found in `src/base`

| PICTURE / usage | Where | Bytes | Java type |
|---|---|---:|---|
| `S9(10)V99` DISPLAY (zoned) | COMMAREA amounts and balances: PROCISRT, ACCOUNT, INQACC, PAYDBCR, XFRFUN | 12 | `Money` + `ZonedDecimal` with `PicSpec.S9_10_V99` |
| `S9(10)V99 COMP-3` | Db2 host variables HV-ACCOUNT-AVAIL-BAL / ACTUAL-BAL (Db2 `DECIMAL(12,2)`) | 7 | `Money` + `PackedDecimal` with `PicSpec.S9_10_V99` |
| `S9(13)V9(2) COMP-3` | IMS ACCOUNT.BALANCE, HISTORY.AMOUNT, IBACSUM/IBTRAN output (DBD `DECIMAL(15,2)`, 8 bytes) | 8 | `Money` + `PackedDecimal` with `PicSpec.S9_13_V99` |
| `S9(4)V99 COMP-3` | Db2 HV-ACCOUNT-INT-RATE (CREACC, DELACC, INQACCCU, INQACCS, XFRFUN, BANKDATA) | 4 | `BigDecimal` + `PicSpec.S9_4_V99` |
| `9(4)V99` DISPLAY | ACCOUNT-INTEREST-RATE / INQACC-INT-RATE | 6 | `BigDecimal` + `PicSpec.U9_4_V99` |
| `S9(9) COMP-5` | IMS CUSTID (CUSTOMER, CUSTACCS, TSTAT), ACCNUM | 4 | `int` (big-endian two's complement) |
| `S9(18) COMP-5` | IMS ACCID, TXID, REFTXID, LASTTXID | 8 | `long` |
| `9(04) COMP` | IMS message LL / ZZ | 2 | unsigned `short` |
| `S9(8) COMP` / `BINARY` | COMM-FACILTYPE, NUMBER-OF-ACCOUNTS | 4 | `int` |
| `S9(15) COMP-3` | ABND-UTIME-KEY, WS-U-TIME | 8 | `long` |
| `FIXED BIN(31)` (PL/I) | IBLOGIN CUSTID, LL_OUT | 4 | `int` |
| `FIXED DEC(12,2)` (PL/I) | BNKSTMT OPENING_BALANCE | 7 | `BigDecimal` scale 2 |

The two stacks don't share a size limit. CICS amounts stop at 9,999,999,999.99 and IMS amounts at 9,999,999,999,999.99. `Money` is scale 2 with no built-in limit; check it against the receiving field with `fits` / `requireFits(PicSpec)`.

## 2. Arithmetic rules

No program in `src/base` uses `ROUNDED` or `ON SIZE ERROR`, so these are the rules to reproduce:

| COBOL behaviour | Java (`nazare.jmp.cobol`) | Example (tested) |
|---|---|---|
| Extra decimals are truncated toward zero | `Money.of(x, CobolRounding.TRUNCATE)`, i.e. `RoundingMode.DOWN` | 10.239 → 10.23; -10.239 → -10.23; 0.005 → 0.00 |
| `ROUNDED` (default NEAREST-AWAY-FROM-ZERO), for future use | `CobolRounding.ROUNDED`, i.e. `RoundingMode.HALF_UP` | 10.235 → 10.24; -10.235 → -10.24; 333.33 × 0.015 = 4.99995 → 5.00 (truncated 4.99) |
| Without ON SIZE ERROR, high-order digits are dropped | `Money.truncateHighOrder(pic)` | 12345678901.23 into S9(10)V99 → 2345678901.23; 9999999999.99 + 0.01 → 0.00 |
| ON SIZE ERROR | `Money.requireFits(pic)` throws `ArithmeticException` | |
| Negative value into an unsigned field keeps the absolute value | `truncateHighOrder(PicSpec.unsigned(..))` | -5.00 → 5.00 |
| `COMPUTE X = A + B` | `plus` / `minus`, exact | 0.10 + 0.20 = 0.30 |
| `COMPUTE X = A * -1` (XFRFUN line 1612) | `times(BigDecimal.ONE.negate(), TRUNCATE)` | |

`Money.of(BigDecimal)` throws on more than 2 decimals, so rounding is never silent. A caller has to pick TRUNCATE or ROUNDED explicitly.

Open point: for multiply/divide, Enterprise COBOL computes intermediate results to a compiler-dependent precision (ARITH(COMPAT) or ARITH(EXTEND)), while `BigDecimal` is exact. Results can differ when an intermediate exceeds 30-31 digits. Nothing in this code base comes close, but confirm the ARITH option before migrating any division.

## 3. Encodings

### 3.1 Zoned decimal (USAGE DISPLAY numeric)

One digit per byte, `X'F0'`-`X'F9'`. On signed pictures the sign is overpunched in the zone nibble of the last byte. Writes use `C` (+) and `D` (-), or `F` for unsigned. Reads accept `F`/`C`/`A`/`E` as positive and `D`/`B` as negative.

```
+123.45  S9(10)V99  F0 F0 F0 F0 F0 F0 F0 F1 F2 F3 F4 C5
-123.45  S9(10)V99  F0 F0 F0 F0 F0 F0 F0 F1 F2 F3 F4 D5
12345678 9(8)       F1 F2 F3 F4 F5 F6 F7 F8
```

Spaces (`X'40'`) or LOW-VALUES in a numeric field are rejected with `IllegalArgumentException`. On the mainframe they cause a data exception (S0C7) as soon as the field is used in arithmetic, or compare unpredictably in an IF. INQACC deliberately uses spaces/LOW-VALUES as a sentinel, but in the **alphanumeric** ACCOUNT-TYPE field, never in a numeric one.

### 3.2 Packed decimal (COMP-3)

Two digits per byte, sign in the last nibble. `S9(n)V9(m)` takes `(n+m)/2 + 1` bytes. If n+m is even, the first nibble is a pad and is always 0.

```
+12345.67  S9(13)V9(2)  00 00 00 00 12 34 56 7C   (8 bytes, matches DBD BYTES=8)
-1.00      S9(10)V99    00 00 00 00 00 10 0D      (7 bytes, leading pad nibble)
+1.25      9(4)V99      00 00 12 5F               (unsigned: F)
```

`PackedDecimal.decode` rejects bad digit nibbles (> 9), bad sign nibbles (0-9), and a non-zero pad nibble. Negative zero (`...0D`) reads as 0.00.

### 3.3 Binary (COMP / COMP-5 / FIXED BIN)

Big-endian two's complement: `ByteBuffer` default order, **not** little-endian. `COMP-5` keeps the full binary range regardless of PICTURE. `COMP` / `BINARY` may be limited to the PICTURE digits depending on the TRUNC compile option; check the option before relying on values beyond the PICTURE.

### 3.4 Character data (EBCDIC)

All IMS DBDs declare `ENCODING=Cp1047`. Use `Ebcdic.CP1047` (`IBM1047`), never the platform default. Things that bite:

- `PIC X(n)` is right-padded with `X'40'`. Trailing spaces don't count in COBOL comparisons. `Ebcdic.readAlphanumeric` drops trailing spaces only, and the DTOs reject values with trailing spaces so that equality stays canonical.
- `[`, `]`, `^`, `|` and `!` are at different code points in 037 and 1047. Use 1047 everywhere, matching the DBDs.
- Sort order: in EBCDIC lower case < upper case < digits; in ASCII it is digits < upper < lower. Any `ORDER BY` or key range on character data sorts differently after migration.
- The `LoadData/*.data` files in git are ASCII/UTF-8 and are converted on upload.
- The frontend upper-cases the customer ID prefix (`C` / `I`) before routing.

### 3.5 Pointers

`POINTER` fields in COMMAREAs (PROCISRT-PCB-POINTER, INQACC-PCB1-POINTER, DELACC-DEL-PCB1..3, COMM-PCB-POINTER) are 4 bytes under 31-bit addressing and appear as `PIC X(4)` in the `*Z` API copybooks. They are addresses inside the region. Java must carry them as opaque bytes (or zeros, as `ProcIsrtCodec` does), never interpret them.

## <a id="procisrt-byte-map"></a>PROCISRT byte map

Offsets are from the start of the 131-byte COMMAREA (no SYNC, so no alignment padding):

| Offset | Len | Debit '1' / Credit '2' | Xfr '3' | Del/Cre customer '4'/'5' | Del/Cre account '6'/'7' |
|---:|---:|---|---|---|---|
| 0 | 1 | FUNCTION | FUNCTION | FUNCTION | FUNCTION |
| 1 | 4 | PCB-POINTER | ← | ← | ← |
| 5 | 8 | ACCNO 9(8) | ← | ← | ← |
| 13 | 6 | SORTCODE 9(6) | ← | ← | ← |
| 19 | 12 | AMOUNT S9(10)V99 | ← | BALANCE | BALANCE |
| 31 | 8 | (debit: FILLER X(100) to 130) | TARGET-ACCNO 9(8) | DOB 9999/99/99 | L-STMNT 9999/99/99 |
| 39 | 8 | | | NAME X(60) (39-98) | N-STMNT 9999/99/99 |
| 47 | 8 | | | | TYPE X(8) |
| 55 | 10 | | | | CUSTNO 9(10) (55-64) |
| 99 | 10 | | | NUMBER 9(10) (99-108) | |

## 4. IMS segments

All databases are HDAM/OSAM, root-only and Cp1047. A Java implementation must reproduce the **key relationships**, not a parent-child tree:

```
CUSTOMER (CUSTID, unique)      CUSTACCS (CUSTID non-unique)        ACCOUNT (ACCID, unique)
  CUSTID INT ───────────────▶   CUSTID INT                           ACCID LONG
                                ACCID LONG ───────────────────────▶  ACCTYPE  'c' | 's'
                                ACCNUM INT (1..n per customer)       BALANCE  DECIMAL(15,2) COMP-3
                                                                     LASTTXID LONG ─▶ HISTORY.TXID
```

| Segment | Field (external name) | Start | Bytes | DBD type | COBOL |
|---|---|---:|---:|---|---|
| CUSTOMER (279) | CUSTID | 1 | 4 | INT, SEQ U | S9(9) COMP-5 |
| | LASTNAME | 5 | 50 | CHAR | X(50) |
| | FIRSTNM (FIRSTNAME) | 55 | 50 | CHAR | X(50) |
| | ADDRESS | 105 | 80 | CHAR | X(80) |
| | CITY | 185 | 25 | CHAR | X(25) |
| | STATE | 210 | 2 | CHAR | X(2) |
| | ZIPCODE | 212 | 15 | CHAR | X(15) |
| | PHONE | 227 | 12 | CHAR | X(12) |
| | STATUS | 239 | 1 | CHAR: '1' logged in, '0' logged out | X(1) |
| | PASSWORD | 240 | 16 | CHAR, **plaintext** | X(16) |
| | CUSTTYPE (CUSTOMERTYPE) | 256 | 1 | CHAR: 'c' regular, 't' teller | X(1) |
| | LASTLOGI (LASTLOGIN) | 257 | 23 | TIMESTAMP, pattern `yyyy-MM-dd HH:mm:ss.SSS` (IBLOGIN writes another format, see inventory §6.4) | X(23) |
| CUSTACCS / CUSTOMERACCS (16) | CUSTID | 1 | 4 | INT, **SEQ M** | S9(9) COMP-5 |
| | CUSACCID | 1 | 12 | BINARY, overlays CUSTID+ACCID (PSB KEYLEN=12) | |
| | ACCID | 5 | 8 | LONG | S9(18) COMP-5 |
| | ACCNUM | 13 | 4 | INT | S9(9) COMP-5 |
| ACCOUNT (25) | ACCID | 1 | 8 | LONG, SEQ U | S9(18) COMP-5 |
| | ACCTYPE | 9 | 1 | CHAR: 'c' checking, 's' savings | X(1) |
| | BALANCE | 10 | 8 | DECIMAL(15,2) | S9(13)V9(2) COMP-3 |
| | LASTTXID | 18 | 8 | LONG | S9(18) COMP-5 |
| HISTORY (56) | TXID 1/8 LONG, TIME 9/23 TIMESTAMP, TRANSTYP 32/1, AMOUNT 33/8 DECIMAL(15,2), REFTXID 41/8, ACCID 49/8 | | | | |

In the sample data, ACCIDs are `<custid><nn>` (customer 1 → 101, 102). That is a data convention, not a rule enforced anywhere.

### 4.1 DL/I status codes the programs handle

| Status | Meaning | How programs react |
|---|---|---|
| blank | OK | continue |
| GE | segment not found | IBLOGIN / IBLOGIN1 / IBACSUM: "does not exist" message |
| GB | end of database | treated the same as GE |
| QC (IOPCB GU) | no more messages | end the message loop |
| CF (IOPCB GU) | message exists | treated as OK |
| anything else | error | `BAD STATUS CODE WAS RECEIVED: ` in MSG-OUT. COBOL moves the whole group, so the code is included; PL/I IBLOGIN assigns only the 30-character text. No abend and no rollback |

In the experimental JDBC reader, GE/GB is an empty `Optional` or list, and any other status is an `SQLException`. IMS JDBC also has `setReturnResultOnStatusCodeGE`; keep it at the default.
