#!/usr/bin/env python3
"""Generate the Phase 0/1 migration inventories from the repository sources.

Outputs (relative to the repo root):
  migration/operation-inventory.json / .md   -- z/OS Connect contract inventory
  migration/copybook-field-mapping.json / .md -- COBOL PIC -> Java type mapping

Usage:
  python3 migration/tools/generate_inventories.py          # regenerate
  python3 migration/tools/generate_inventories.py --check  # fail if outputs are stale
"""
import argparse
import json
import re
import sys
import urllib.parse
import xml.etree.ElementTree as ET
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
API = ROOT / "src/api/src/main"
ZOS_ASSETS = API / "zosAssets"
OUT = ROOT / "migration"
CICS_COPY = ROOT / "src/base/cics/copy"
IMS_COPY = ROOT / "src/base/ims/copy"
CICS_COBOL = ROOT / "src/base/cics/cobol"
IMS_COBOL = ROOT / "src/base/ims/cobol"
IMS_PSB = ROOT / "src/base/ims/PSB"
DB2_DDL = ROOT / ".setup/jcl/cics/Db2-create.j2"
PHASE2_FIRST_WAVE = ["INQCUST", "INQACC", "INQACCS", "INQTRAND", "INQTRANL"]

DATE_NAME = re.compile(r"(DATE|DOB|-DT$|OPENED$|STMT)")
TIME_NAME = re.compile(r"TIME$")


def rel(p):
    return str(Path(p).relative_to(ROOT)) if p else None


# --------------------------------------------------------------------------- COBOL source

def source_lines(path):
    """Fixed-format COBOL: drop sequence area and comment lines, keep cols 8-72."""
    out = []
    for raw in path.read_text(encoding="utf-8", errors="replace").splitlines():
        if len(raw) > 6 and raw[6] in "*/":
            continue
        out.append(raw[7:72] if len(raw) > 7 else "")
    return out


def split_statements(lines):
    text = " ".join(lines)
    stmts, cur, quote = [], [], None
    for i, ch in enumerate(text):
        if quote:
            cur.append(ch)
            if ch == quote:
                quote = None
            continue
        if ch in "'\"":
            quote = ch
        nxt = text[i + 1] if i + 1 < len(text) else " "
        if ch == "." and nxt.isspace():
            s = "".join(cur).strip()
            if s:
                stmts.append(s)
            cur = []
            continue
        cur.append(ch)
    s = "".join(cur).strip()
    if s:
        stmts.append(s)
    return stmts


def tokenize(stmt):
    return re.findall(r"X'[0-9A-Fa-f]*'|'[^']*'|\"[^\"]*\"|\S+", stmt)


def expand_pic(pic):
    out = ""
    for m in re.finditer(r"(CR|DB|.)(?:\((\d+)\))?", pic.upper()):
        out += m.group(1) * (int(m.group(2)) if m.group(2) else 1)
    return out


def analyse_pic(pic, usage, sign_separate):
    exp = expand_pic(pic)
    if re.fullmatch(r"[XA]+", exp):
        return dict(category="alphanumeric", length=len(exp), size=len(exp))
    if re.fullmatch(r"N+", exp):
        return dict(category="national", length=len(exp), size=2 * len(exp))
    if re.fullmatch(r"S?[9P]*V?[9P]*", exp):
        signed = exp.startswith("S")
        body = exp.lstrip("S")
        int_part, _, frac = body.partition("V")
        digits = int_part.count("9") + frac.count("9")
        scale = frac.count("9")
        if usage in ("COMP-3", "PACKED-DECIMAL"):
            size = digits // 2 + 1
        elif usage in ("COMP", "COMP-4", "BINARY", "COMP-5"):
            size = 2 if digits <= 4 else 4 if digits <= 9 else 8
        else:
            size = digits + (1 if sign_separate else 0)
        return dict(category="numeric", digits=digits, scale=scale, signed=signed, size=size)
    size = len(exp.replace("CR", "C").replace("DB", "D")) + exp.count("CR") + exp.count("DB")
    return dict(category="numeric-edited", size=size)


USAGE_WORDS = {"COMP", "COMP-1", "COMP-2", "COMP-3", "COMP-4", "COMP-5", "COMPUTATIONAL",
               "COMPUTATIONAL-3", "COMPUTATIONAL-5", "BINARY", "PACKED-DECIMAL", "DISPLAY",
               "POINTER", "PROCEDURE-POINTER", "FUNCTION-POINTER", "INDEX", "NATIONAL"}


def parse_entry(stmt):
    toks = tokenize(stmt)
    if not toks or not toks[0].isdigit():
        return None
    e = dict(level=int(toks[0]), name="FILLER", pic=None, usage=None, redefines=None,
             occurs=None, value=None, signSeparate=False, raw=" ".join(stmt.split()))
    i = 1
    if i < len(toks) and toks[i].upper() not in ("PIC", "PICTURE", "REDEFINES", "OCCURS", "VALUE", "USAGE") \
            and toks[i].upper() not in USAGE_WORDS:
        e["name"] = toks[i]
        i += 1
    while i < len(toks):
        t = toks[i].upper()
        if t in ("PIC", "PICTURE"):
            i += 1
            if i < len(toks) and toks[i].upper() == "IS":
                i += 1
            e["pic"] = toks[i]
        elif t == "REDEFINES":
            i += 1
            e["redefines"] = toks[i]
        elif t == "OCCURS":
            lo = hi = int(toks[i + 1])
            i += 2
            dep = None
            if i < len(toks) and toks[i].upper() == "TO":
                hi = int(toks[i + 1])
                i += 2
            while i < len(toks) and toks[i].upper() in ("TIMES", "DEPENDING", "ON"):
                if toks[i].upper() == "DEPENDING":
                    i += 1
                    if toks[i].upper() == "ON":
                        i += 1
                    dep = toks[i]
                    break
                i += 1
            e["occurs"] = dict(min=lo, max=hi, dependingOn=dep)
        elif t in ("VALUE", "VALUES"):
            rest = toks[i + 1:]
            if rest and rest[0].upper() in ("IS", "ARE"):
                rest = rest[1:]
            e["value"] = " ".join(rest)
            break
        elif t == "USAGE":
            pass
        elif t == "SEPARATE":
            e["signSeparate"] = True
        elif t in USAGE_WORDS:
            e["usage"] = {"COMPUTATIONAL": "COMP", "COMPUTATIONAL-3": "COMP-3",
                          "COMPUTATIONAL-5": "COMP-5"}.get(t, t)
        i += 1
    return e


def build_records(path):
    """Parse a copybook into a list of 01/top-level record trees with offsets."""
    lines, sql, in_sql = [], [], False
    for ln in source_lines(path):
        if "EXEC SQL" in ln.upper():
            in_sql = True
        if in_sql:
            sql.append(ln)
            if "END-EXEC" in ln.upper():
                in_sql = False
            continue
        lines.append(ln)
    entries = [e for e in (parse_entry(s) for s in split_statements(lines)) if e]
    roots, stack = [], []
    for e in entries:
        if e["level"] == 88:
            if stack:
                stack[-1].setdefault("conditions", []).append({"name": e["name"], "value": e["value"]})
            continue
        if e["level"] == 66:
            continue
        e["children"] = []
        while stack and stack[-1]["level"] >= e["level"]:
            stack.pop()
        (stack[-1]["children"] if stack else roots).append(e)
        stack.append(e)
    # treat multiple top-level 03/05 items as one implicit record named after the file
    if len(roots) > 1 and all(r["level"] != 1 for r in roots):
        roots = [dict(level=0, name=path.stem + " (implicit record)", children=roots, pic=None,
                      usage=None, redefines=None, occurs=None, value=None, raw="(no 01 level)")]
    for r in roots:
        layout(r, 0, None)
    return roots, parse_sql_declare(sql)


def layout(node, offset, inherited_usage):
    usage = node.get("usage") or inherited_usage
    node["offset"] = offset
    if node["children"]:
        cur, by_name = offset, {}
        for c in node["children"]:
            start = by_name[c["redefines"]]["offset"] if c.get("redefines") in by_name else cur
            layout(c, start, usage)
            by_name[c["name"]] = c
            if not c.get("redefines"):
                cur = start + c["totalSize"]
        node["size"] = cur - offset
    elif node.get("pic"):
        node.update(analyse_pic(node["pic"], usage or "DISPLAY", node.get("signSeparate")))
        node["usage"] = usage or "DISPLAY"
    elif usage in ("POINTER", "PROCEDURE-POINTER", "FUNCTION-POINTER", "INDEX"):
        node.update(category=usage.lower(), size=8 if usage == "PROCEDURE-POINTER" else 4)
        node["usage"] = usage
    elif usage in ("COMP-1", "COMP-2"):
        node.update(category="floating-point", size=4 if usage == "COMP-1" else 8)
        node["usage"] = usage
    else:
        node["size"] = 0
    occ = node.get("occurs")
    node["totalSize"] = node["size"] * (occ["max"] if occ else 1)


def date_layout(group):
    names = [c["name"] for c in group.get("children", [])]
    tags = []
    for n in names:
        for suffix, tag in (("YYYY", "YYYY"), ("YEAR", "YYYY"), ("MM", "MM"), ("MONTH", "MM"),
                            ("DD", "DD"), ("DAY", "DD"), ("HH", "HH"), ("SS", "SS")):
            if n.endswith(suffix):
                tags.append(tag)
                break
    return "".join(tags) if len(tags) == len(names) and tags else None


def java_mapping(n, siblings):
    cat = n.get("category")
    occ = n.get("occurs")
    notes = []
    if n.get("redefines"):
        notes.append(f"REDEFINES {n['redefines']}: overlay view of the same {n['totalSize']} bytes, "
                     "not separate storage; decode on demand, never encode both views")
    if occ:
        if occ["dependingOn"]:
            notes.append(f"OCCURS {occ['min']} TO {occ['max']} DEPENDING ON {occ['dependingOn']}: "
                         "java.util.List sized by the counter field")
        else:
            notes.append(f"OCCURS {occ['max']}: fixed-size array, unused slots are spaces/zeros")
    if n["children"]:
        java = "record/class (group)"
        lay = date_layout(n)
        if lay and re.search(r"DATE|DOB|STMT|OPENED|TIME", n["name"]):
            notes.append(f"date/time group laid out as {lay}; keep raw digits, no java.time conversion")
    elif cat == "alphanumeric":
        java = "java.lang.String"
        notes.append(f"fixed-length {n['length']} bytes, EBCDIC (IBM037) <-> Java String; pad with spaces")
    elif cat == "national":
        java = "java.lang.String"
        notes.append("PIC N national (UTF-16BE)")
    elif cat == "numeric":
        java = "java.math.BigDecimal"
        enc = {"COMP-3": "packed decimal", "PACKED-DECIMAL": "packed decimal",
               "COMP-5": "native binary (full range)"}.get(n["usage"], None)
        if enc is None:
            enc = "binary big-endian (TRUNC applies)" if n["usage"] in ("COMP", "COMP-4", "BINARY") else \
                ("zoned decimal, trailing overpunch sign" if n["signed"] else "zoned decimal, unsigned (F zone)")
        notes.append(f"{enc}; precision {n['digits']}, scale {n['scale']}; never double/float")
        if n["digits"] == 8 and n["scale"] == 0 and DATE_NAME.search(n["name"]):
            overlay = next((s for s in siblings if s.get("redefines") == n["name"]), None)
            lay = date_layout(overlay) if overlay else None
            notes.append("raw 8-digit date" + (f" ({lay} per REDEFINES {overlay['name']})" if lay else "")
                         + "; keep as 8 zero-padded digits, do not convert to java.time")
        elif n["digits"] == 6 and n["scale"] == 0 and TIME_NAME.search(n["name"]):
            notes.append("raw 6-digit time HHMMSS; keep as zero-padded digits")
    elif cat == "numeric-edited":
        java = "java.lang.String"
        notes.append(f"numeric-edited PIC {n['pic']} (display only); parse with new BigDecimal(trimmed) at the boundary")
    elif cat == "floating-point":
        java = "UNSUPPORTED (float) - convert to BigDecimal"
        notes.append("COMP-1/COMP-2 must not reach business logic")
    elif cat in ("pointer", "procedure-pointer", "function-pointer", "index"):
        java = "byte[] (opaque)"
        notes.append("address/index value, not business data; not mapped to the API")
    else:
        java = "(no storage)"
    return java, notes


def flatten(node, path, out, siblings=()):
    p = f"{path}.{node['name']}" if path else node["name"]
    java, notes = java_mapping(node, siblings)
    row = dict(level=node["level"], name=node["name"], path=p, offset=node["offset"], size=node["size"],
               totalSize=node["totalSize"], pic=node.get("pic"), usage=node.get("usage"),
               category=node.get("category", "group" if node["children"] else None), javaType=java)
    for k in ("digits", "scale", "signed", "length"):
        if k in node:
            row[k] = node[k]
    if node.get("redefines"):
        row["redefines"] = node["redefines"]
    if node.get("occurs"):
        row["occurs"] = node["occurs"]
    if node.get("conditions"):
        row["conditions88"] = node["conditions"]
    if node.get("value"):
        row["value"] = node["value"]
    row["notes"] = notes
    out.append(row)
    for c in node["children"]:
        flatten(c, p, out, node["children"])


SQL_JAVA = {"CHAR": "java.lang.String", "VARCHAR": "java.lang.String", "DECIMAL": "java.math.BigDecimal",
            "INTEGER": "java.math.BigDecimal (scale 0; JDBC getInt is exact too)",
            "SMALLINT": "java.math.BigDecimal (scale 0)", "BIGINT": "java.math.BigDecimal (scale 0)",
            "DATE": "java.sql.Date / java.time.LocalDate via JDBC (Db2 DATE column, not a PIC field)",
            "TIMESTAMP": "java.sql.Timestamp", "TIME": "java.sql.Time"}


def parse_sql_declare(sql_lines):
    if not sql_lines:
        return None
    text = " ".join(sql_lines)
    m = re.search(r"DECLARE\s+(\S+)\s+TABLE\s*\((.*)\)\s*END-EXEC", text, re.S | re.I)
    if not m:
        return None
    cols = []
    for part in re.split(r",\s*(?![^()]*\))", m.group(2)):
        cm = re.match(r"\s*(\w+)\s+(\w+)(?:\s*\(([^)]*)\))?(.*)", part)
        if cm:
            t = cm.group(2).upper()
            cols.append(dict(column=cm.group(1), sqlType=t + (f"({cm.group(3).replace(' ', '')})" if cm.group(3) else ""),
                             notNull="NOT NULL" in cm.group(4).upper(), javaType=SQL_JAVA.get(t, "?")))
    return dict(table=m.group(1), columns=cols)


def ddl_columns():
    if not DB2_DDL.exists():
        return {}
    text = DB2_DDL.read_text(encoding="utf-8", errors="replace")
    tables = {}
    for m in re.finditer(r"CREATE\s+TABLE\s+(?:[\w{}.\s]*\.)?(\w+)\s*\((.*?)\)\s*(?:IN|;|CCSID)", text, re.S | re.I):
        cols = {}
        for part in re.split(r",\s*(?![^()]*\))", m.group(2)):
            cm = re.match(r"\s*(\w+)\s+(\w+)(?:\s*\(([^)]*)\))?", part)
            if cm and cm.group(1).upper() not in ("PRIMARY", "CONSTRAINT", "UNIQUE", "FOREIGN"):
                cols[cm.group(1).upper()] = cm.group(2).upper() + (f"({cm.group(3).replace(' ', '')})" if cm.group(3) else "")
        tables[m.group(1).upper()] = cols
    return tables


# --------------------------------------------------------------------------- copybook users

def program_refs():
    """Map copybook name -> programs that COPY / EXEC SQL INCLUDE it."""
    refs = {}
    for prog in sorted(list(CICS_COBOL.glob("*.cbl")) + list(IMS_COBOL.glob("*.cbl"))):
        text = " ".join(source_lines(prog)).upper()
        for name in set(re.findall(r"\bCOPY\s+([A-Z0-9#@$-]+)", text)) | set(re.findall(r"\bINCLUDE\s+([A-Z0-9#@$-]+)", text)):
            refs.setdefault(name.rstrip("."), []).append(rel(prog))
    return refs


def program_facts(prog, known_tables=()):
    if not prog or not prog.exists():
        return {}
    text = " ".join(source_lines(prog)).upper()
    sql_tables = set()
    for blk in re.findall(r"EXEC\s+SQL(.*?)END-EXEC", text, re.S):
        for kw, name in re.findall(r"\b(FROM|INTO|UPDATE|JOIN)\s+([A-Z_][A-Z0-9_.]*)", blk):
            if not name.startswith(":") and name not in ("SQLCA",):
                sql_tables.add(name)
    facts = dict(
        copybooks=sorted(set(n.rstrip(".") for n in re.findall(r"\bCOPY\s+([A-Z0-9#@$-]+)", text))),
        sqlIncludes=sorted(set(re.findall(r"EXEC\s+SQL\s+INCLUDE\s+([A-Z0-9]+)", text))),
        db2Tables=sorted(t for t in sql_tables if not known_tables or t in known_tables),
        cicsLinkLiterals=sorted(set(re.findall(r"LINK\s+PROGRAM\s*\(\s*'([A-Z0-9]+)'", text))),
        cicsRunTransid=sorted(set(re.findall(r"RUN\s+TRANSID\s*\(\s*'?([A-Z0-9-]+)'?", text))),
        calls=sorted(set(re.findall(r"\bCALL\s+'([A-Z0-9]+)'", text))),
        syncpoint="SYNCPOINT" in text,
        handleAbend="HANDLE ABEND" in text,
        cicsAbend=sorted(set(re.findall(r"ABEND\s+ABCODE\s*\(\s*'([A-Z0-9]+)'", text))),
    )
    return {k: v for k, v in facts.items() if v}


def psb_pcbs(name):
    psb = IMS_PSB / f"{name}.asm"
    if not psb.exists():
        return None, []
    text = psb.read_text(encoding="utf-8", errors="replace")
    pcbs = [dict(dbd=d, procopt=p) for d, p in re.findall(r"DBDNAME=(\w+),PROCOPT=(\w+)", text)]
    return rel(psb), pcbs


# --------------------------------------------------------------------------- .dai cross-check

def dai_fields(path):
    root = ET.parse(path).getroot()
    out = {}
    for f in root.iter("field"):
        sp, by = f.find("startPos"), f.find("bytes")
        if sp is not None and by is not None:
            out[f.get("path")] = (int(sp.text) - 1, int(by.text))
    return out


def cross_check(records, dai):
    rows = []
    for r in records:
        flat = []
        flatten(r, "", flat)
        rows.extend(flat)
    checked = mismatches = 0
    details = []
    record_note = None
    for row in rows:
        if row["path"] in dai:
            off, size = dai[row["path"]]
            if "." not in row["path"]:
                if size != row["totalSize"]:
                    record_note = dict(daiRecordBytes=size, sumOfFields=row["totalSize"])
                continue
            checked += 1
            if (off, size) != (row["offset"], row["totalSize"]):
                mismatches += 1
                details.append(dict(path=row["path"], parsed=[row["offset"], row["size"]], dai=[off, size]))
    out = dict(fieldsCompared=checked, mismatches=mismatches, details=details)
    if record_note:
        out["recordLengthNote"] = record_note
    return out


# --------------------------------------------------------------------------- operations

def api_operations():
    spec = yaml.safe_load((API / "api/openapi.yaml").read_text(encoding="utf-8"))
    by_asset = {}
    for op_dir in sorted(p for p in (API / "operations").iterdir() if p.is_dir()):
        path = urllib.parse.unquote(op_dir.name)
        for mdir in sorted(op_dir.iterdir()):
            opyaml = yaml.safe_load((mdir / "operation.yaml").read_text(encoding="utf-8"))
            spec_op = spec["paths"].get(path, {}).get(mdir.name, {})
            ok = next((c for c in ("200", "201", "204") if c in spec_op.get("responses", {})), None)
            schema = (spec_op.get("responses", {}).get(ok, {}) or {}).get("content", {}) \
                .get("application/json", {}).get("schema", {}).get("$ref") if ok else None
            body = spec_op.get("requestBody", {}).get("content", {}).get("application/json", {}) \
                .get("schema", {}).get("$ref")
            mapping = mdir / "response_mapping.yaml"
            statuses = sorted(yaml.safe_load(mapping.read_text(encoding="utf-8")).keys()) if mapping.exists() else []
            by_asset.setdefault(opyaml["zasset"], []).append(dict(
                method=mdir.name.upper(), path=path, operationId=spec_op.get("operationId"),
                readOnly=mdir.name == "get", successStatus=ok,
                requestBodySchema=body.split("/")[-1] if body else None,
                responseSchema=schema.split("/")[-1] if schema else None,
                declaredStatuses=sorted(spec_op.get("responses", {}).keys()),
                operationDir=rel(mdir), mappedResponseFiles=statuses))
    return spec, by_asset


def resolve_copybook(name):
    for d in (CICS_COPY, IMS_COPY, IMS_COBOL, CICS_COBOL):
        p = d / name
        if p.exists():
            return rel(p)
    return None


def build_operation_inventory():
    spec, by_asset = api_operations()
    ops = []
    for asset in sorted(p for p in ZOS_ASSETS.iterdir() if p.is_dir()):
        meta = yaml.safe_load((asset / "zosAsset.yaml").read_text(encoding="utf-8"))
        props = meta.get("providerProperties", {})
        backend = "CICS" if meta["type"].startswith("cics") else "IMS"
        program = props.get("program") or props.get("transactionCode")
        prog_path = (CICS_COBOL if backend == "CICS" else IMS_COBOL) / f"{program}.cbl"
        pf = asset / "providerFiles"
        sources = {}
        for kind in ("request", "response"):
            root = ET.parse(pf / f"{kind}.dai").getroot()
            f = next(root.iter("field"))
            sources[kind] = dict(sourceFile=f.get("fileName"), sourcePath=resolve_copybook(f.get("fileName")),
                                 generatedCopybook=rel(pf / "gen" / f.get("structSrcFileName")),
                                 topLevel=f.get("name"), bytes=int(f.find("bytes").text))
        entry = dict(
            operation=asset.name, backend=backend, zosAssetType=meta["type"],
            description=meta.get("description") or None, zosAssetPath=rel(asset),
            requestSchema=rel(pf / "gen/requestSchema.json"), responseSchema=rel(pf / "gen/responseSchema.json"),
            requestLayout=sources["request"], responseLayout=sources["response"],
            providerProperties=props, cobolProgram=rel(prog_path) if prog_path.exists() else None,
            programFacts=program_facts(prog_path, set(ddl_columns())),
            readOnly=all(o["readOnly"] for o in by_asset.get(asset.name, [])) if by_asset.get(asset.name) else None,
            phase2FirstWave=asset.name in PHASE2_FIRST_WAVE,
            apiOperations=by_asset.get(asset.name, []))
        if backend == "IMS":
            psb, pcbs = psb_pcbs(program)
            entry["imsPsb"], entry["imsPcbs"] = psb, pcbs
            entry["imsDbds"] = [rel(ROOT / f"src/base/ims/DBD/{p['dbd']}.asm") for p in pcbs]
        ops.append(entry)
    spec_ops = [(p, m) for p, v in spec["paths"].items() for m in v if m in ("get", "post", "put", "delete", "patch")]
    mapped = {(o["path"], o["method"].lower()) for e in ops for o in e["apiOperations"]}
    return dict(
        generatedBy=rel(Path(__file__)), openapiSpec=rel(API / "api/openapi.yaml"),
        openapiVersion=spec.get("openapi"), apiBasePath=spec.get("servers", [{}])[0].get("url"),
        counts=dict(zosAssets=len(ops), openapiPaths=len(spec["paths"]), openapiOperations=len(spec_ops),
                    cicsAssets=sum(o["backend"] == "CICS" for o in ops), imsAssets=sum(o["backend"] == "IMS" for o in ops)),
        openapiOperationsWithoutZosAsset=[f"{m.upper()} {p}" for p, m in spec_ops if (p, m) not in mapped],
        operations=ops)


# --------------------------------------------------------------------------- copybooks

def build_copybook_mapping():
    refs = program_refs()
    ddl = ddl_columns()
    books = []
    files = sorted(CICS_COPY.glob("*.cpy")) + sorted(IMS_COPY.glob("*.cpy")) + \
        sorted(ZOS_ASSETS.glob("*/providerFiles/*.cpy")) + sorted(ZOS_ASSETS.glob("*/providerFiles/gen/*.cpy"))
    dai_index = {}
    for asset in ZOS_ASSETS.iterdir():
        for kind in ("request", "response"):
            dai = asset / "providerFiles" / f"{kind}.dai"
            if dai.exists():
                root = ET.parse(dai).getroot()
                f = next(root.iter("field"))
                dai_index.setdefault(asset / "providerFiles/gen" / f.get("structSrcFileName"), []).append(dai)
    for f in files:
        records, sql = build_records(f)
        kind = "zosconnect-generated" if "/gen/" in str(f) else \
            ("zosconnect-provider" if "zosAssets" in str(f) else ("ims" if f.parent == IMS_COPY else "cics"))
        entry = dict(copybook=f.stem, path=rel(f), kind=kind,
                     usedBy=refs.get(f.stem, []) if kind in ("cics", "ims") else None)
        if sql:
            entry["db2Declare"] = sql
            live = ddl.get(sql["table"].upper())
            if live:
                drift = [dict(column=c["column"], copybook=c["sqlType"], ddl=live.get(c["column"].upper()))
                         for c in sql["columns"] if live.get(c["column"].upper()) not in (None, c["sqlType"])]
                entry["db2DdlDrift"] = drift
        recs = []
        for r in records:
            flat = []
            flatten(r, "", flat)
            recs.append(dict(name=r["name"], size=r["totalSize"], fields=flat))
        entry["records"] = recs
        if f in dai_index:
            entry["daiCrossCheck"] = {rel(d): cross_check(records, dai_fields(d)) for d in dai_index[f]}
        books.append(entry)
    return dict(
        generatedBy=rel(Path(__file__)),
        rules={
            "numeric": "All PIC 9 / S9 fields (DISPLAY zoned, COMP/BINARY, COMP-3/PACKED-DECIMAL, COMP-5) map to java.math.BigDecimal with the PIC scale. double/float are forbidden.",
            "alphanumeric": "PIC X(n)/A(n) map to a fixed-length java.lang.String; bytes are EBCDIC IBM037 (CCSID 037, as declared in zosAsset.yaml) and are decoded/encoded with an explicit charset, padded with spaces.",
            "dates": "PIC 9(8) date fields stay raw 8-digit values (layout DDMMYYYY or YYYYMMDD given per field); no automatic java.time conversion.",
            "redefines": "REDEFINES are overlays over the same bytes (decode either view, encode only one).",
            "occurs": "OCCURS n are fixed arrays; OCCURS m TO n DEPENDING ON c are java.util.List sized by counter c.",
            "edited": "Numeric-edited PICs (Z, ., -) are display strings; parse to BigDecimal at the boundary.",
            "binary": "COMP/BINARY sizes: 1-4 digits 2 bytes, 5-9 4 bytes, 10-18 8 bytes, big-endian; COMP-5 uses the full binary range.",
        },
        counts=dict(total=len(books), **{k: sum(b["kind"] == k for b in books) for k in
                                         ("cics", "ims", "zosconnect-provider", "zosconnect-generated")}),
        copybooks=books)


# --------------------------------------------------------------------------- markdown

def md_operations(inv):
    c = inv["counts"]
    out = ["# Operation inventory (Phase 0 contract freeze)", "",
           f"Generated by `{inv['generatedBy']}` from `{inv['openapiSpec']}` and `src/api/src/main/zosAssets/`. "
           "Do not edit by hand; rerun the generator.", "",
           f"- z/OS Connect assets: **{c['zosAssets']}** ({c['cicsAssets']} CICS, {c['imsAssets']} IMS)",
           f"- OpenAPI {inv['openapiVersion']}: **{c['openapiOperations']} operations** on **{c['openapiPaths']} paths**, base path `{inv['apiBasePath']}`",
           f"- OpenAPI operations with no zosAsset: {', '.join(inv['openapiOperationsWithoutZosAsset']) or 'none'}", "",
           "## Assets", "",
           "| Operation | Backend | Program / transid | COBOL program | Request layout (bytes) | Response layout (bytes) | Schemas | Read-only | Phase 2 wave 1 |",
           "|---|---|---|---|---|---|---|---|---|"]
    for o in inv["operations"]:
        pp = o["providerProperties"]
        tx = f"{pp.get('program')} / {pp.get('transid')} (CCSID {pp.get('ccsid')})" if o["backend"] == "CICS" \
            else f"IMS trancode {pp.get('transactionCode')}"
        rq, rs = o["requestLayout"], o["responseLayout"]
        out.append(f"| {o['operation']} | {o['backend']} | {tx} | `{o['cobolProgram']}` | "
                   f"{rq['topLevel']} from `{rq['sourceFile']}` ({rq['bytes']}) | {rs['topLevel']} from `{rs['sourceFile']}` ({rs['bytes']}) | "
                   f"[req]({'../' + o['requestSchema']}) / [resp]({'../' + o['responseSchema']}) | "
                   f"{'yes' if o['readOnly'] else 'no'} | {'yes' if o['phase2FirstWave'] else ''} |")
    out += ["", "## OpenAPI operations", "", "| Method | Path | operationId | zosAsset | Success | Body schema | Response schema |",
            "|---|---|---|---|---|---|---|"]
    rows = sorted(((a["path"], a["method"], a, o["operation"]) for o in inv["operations"] for a in o["apiOperations"]))
    for path, method, a, asset in rows:
        out.append(f"| {method} | `{path}` | {a['operationId']} | {asset} | {a['successStatus']} | "
                   f"{a['requestBodySchema'] or ''} | {a['responseSchema'] or ''} |")
    out += ["", "## Backend program facts", "", "| Operation | Copybooks | Db2 tables | IMS PSB / PCBs | CICS semantics |", "|---|---|---|---|---|"]
    for o in inv["operations"]:
        f = o["programFacts"]
        books = ", ".join(f.get("copybooks", []) + [f"SQL INCLUDE {x}" for x in f.get("sqlIncludes", [])])
        ims = ""
        if o["backend"] == "IMS":
            ims = f"`{o['imsPsb']}`: " + ", ".join(f"{p['dbd']}({p['procopt']})" for p in o["imsPcbs"])
        sem = ", ".join(x for x in [
            "HANDLE ABEND" if f.get("handleAbend") else "", "SYNCPOINT" if f.get("syncpoint") else "",
            ("ABEND " + "/".join(f["cicsAbend"])) if f.get("cicsAbend") else "",
            ("RUN TRANSID " + "/".join(f["cicsRunTransid"])) if f.get("cicsRunTransid") else "",
            ("LINK " + "/".join(f["cicsLinkLiterals"])) if f.get("cicsLinkLiterals") else "",
            ("CALL " + "/".join(f["calls"])) if f.get("calls") else ""] if x)
        out.append(f"| {o['operation']} | {books} | {', '.join(f.get('db2Tables', []))} | {ims} | {sem} |")
    return "\n".join(out) + "\n"


def md_copybooks(m):
    c = m["counts"]
    out = ["# Copybook field mapping (Phase 1 data fidelity)", "",
           f"Generated by `{m['generatedBy']}`. Machine-readable version: [copybook-field-mapping.json](copybook-field-mapping.json). "
           "Offsets are 0-based byte offsets inside the record.", "",
           f"Copybooks: **{c['total']}** ({c['cics']} under `src/base/cics/copy`, {c['ims']} under `src/base/ims/copy`, "
           f"{c['zosconnect-provider']} provider copies under `zosAssets/*/providerFiles`, {c['zosconnect-generated']} generated under `zosAssets/*/providerFiles/gen`).", "",
           "## Mapping rules", ""]
    out += [f"- **{k}**: {v}" for k, v in m["rules"].items()]
    out += ["", "## Summary", "", "| Copybook | Kind | Records (bytes) | Used by | Layout check |", "|---|---|---|---|---|"]
    for b in m["copybooks"]:
        recs = ", ".join(f"{r['name']} ({r['size']})" for r in b["records"]) or \
            (f"Db2 DECLARE {b['db2Declare']['table']}" if b.get("db2Declare") else "(none)")
        used = "orphan (no COPY/INCLUDE)" if b["usedBy"] == [] else \
            (", ".join(Path(p).stem for p in b["usedBy"]) if b["usedBy"] else "z/OS Connect")
        chk = ""
        if b.get("daiCrossCheck"):
            chk = "; ".join(f"{v['fieldsCompared']} fields vs .dai, {v['mismatches']} mismatches"
                            + (f" (.dai record {v['recordLengthNote']['daiRecordBytes']} B vs fields {v['recordLengthNote']['sumOfFields']} B)"
                               if v.get("recordLengthNote") else "") for v in b["daiCrossCheck"].values())
        if b.get("db2DdlDrift"):
            chk += " DDL drift: " + ", ".join(f"{d['column']} {d['copybook']}→{d['ddl']}" for d in b["db2DdlDrift"])
        out.append(f"| [{b['copybook']}](../{b['path']}) | {b['kind']} | {recs} | {used} | {chk} |")
    out += ["", "## Field tables", ""]
    for b in m["copybooks"]:
        out += [f"### {b['copybook']} — `{b['path']}`", ""]
        if b.get("db2Declare"):
            d = b["db2Declare"]
            out += [f"Db2 `DECLARE {d['table']} TABLE` (host-variable layout, not a COMMAREA):", "",
                    "| Column | SQL type | Java type |", "|---|---|---|"]
            out += [f"| {col['column']} | {col['sqlType']}{' NOT NULL' if col['notNull'] else ''} | {col['javaType']} |" for col in d["columns"]]
            out.append("")
        for r in b["records"]:
            out += [f"Record **{r['name']}** — {r['size']} bytes", "",
                    "| Lvl | Field | Offset | Size | PIC / usage | Java type | Notes |", "|---|---|---|---|---|---|---|"]
            for f in r["fields"]:
                pic = f"{f['pic'] or ''} {f['usage'] or ''}".strip() if f["pic"] or f["category"] not in ("group",) else ""
                notes = "; ".join(f["notes"])
                if f.get("conditions88"):
                    notes += ("; " if notes else "") + "88: " + ", ".join(f"{c['name']}={c['value']}" for c in f["conditions88"])
                size = f"{f['size']}×{f['occurs']['max']}" if f.get("occurs") else str(f["size"])
                out.append(f"| {f['level']:02d} | {'&nbsp;' * 2 * max(0, f['path'].count('.'))}{f['name']} | {f['offset']} | {size} | {pic} | {f['javaType']} | {notes.replace('|', '/')} |")
            out.append("")
    return "\n".join(out) + "\n"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args()
    inv, cb = build_operation_inventory(), build_copybook_mapping()
    outputs = {
        OUT / "operation-inventory.json": json.dumps(inv, indent=2) + "\n",
        OUT / "operation-inventory.md": md_operations(inv),
        OUT / "copybook-field-mapping.json": json.dumps(cb, indent=1) + "\n",
        OUT / "copybook-field-mapping.md": md_copybooks(cb),
    }
    stale = [p for p, txt in outputs.items() if not p.exists() or p.read_text(encoding="utf-8") != txt]
    if args.check:
        if stale:
            print("Stale outputs: " + ", ".join(rel(p) for p in stale))
            return 1
        print("Inventories are up to date")
        return 0
    for p, txt in outputs.items():
        p.write_text(txt, encoding="utf-8")
    bad = [(b["path"], k) for b in cb["copybooks"] for k, v in b.get("daiCrossCheck", {}).items() if v["mismatches"]]
    print(f"{inv['counts']}  copybooks={cb['counts']}  dai mismatches={bad or 'none'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
