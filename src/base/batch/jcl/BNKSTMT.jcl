//BNKSTMT JOB 'Report',NOTIFY=&SYSUID,CLASS=A,MSGCLASS=H,
//          MSGLEVEL=(1,1),REGION=4M
//********************************************
//* PHASE 1 PARALLEL RUN: THE PL/I AND JAVA BNKSTMT READ THE SAME
//* CARDS AND DB2 TABLES; STEP3 DIFFS THE TWO REPORTS.
//* RC 0 IDENTICAL, 4 TEXT DIFFERENCES, 8 NUMERIC DIFFERENCE (BLOCKER).
//* KEEP THE DATECARD AND SORTCODE CARDS OF STEP1 AND STEP2 IDENTICAL.
//* CUTOVER (AFTER CLEAN PARALLEL RUNS): DROP STEP1, STEP3 AND THE
//* PLIRPT PRINT, AND POINT STEP2 SYSPRINT AT SYSOUT=*.
//********************************************
// SET JZOSLIB='JZOS.LOADLIB'
// SET BANKZJAR='/u/bankz/java/lib'
//STEP1    EXEC PGM=IKJEFT01
//STEPLIB  DD  DISP=SHR,DSN=DB2V13.SDSNEXIT
//         DD  DISP=SHR,DSN=DB2V13.SDSNLOAD
//********************************************
//*MONTH FOR WHICH REPORT IS RUN
//********************************************
//DATECARD DD *
202606
//********************************************
//*ACCOUNT IDENTIFIER FOR WHICH REPORT IS RUN
//********************************************
//SORTCODE DD *
123456
//SYSPRINT DD   DSN=&&PLIRPT,DISP=(NEW,PASS),UNIT=SYSDA,
//              SPACE=(CYL,(5,5)),DCB=(RECFM=VBA,LRECL=125,BLKSIZE=0)
//SYSOUT   DD   SYSOUT=*
//SYSTSPRT DD   SYSOUT=*
//SYSTSIN  DD *
 DSN SYSTEM(DBD1)
 RUN  PROGRAM(BNKSTMT) PLAN(BANKZPLN) -
      LIB('BANKZ.V0R1M0.LOAD')
 END
/*
//********************************************
//* JAVA BNKSTMT UNDER THE JZOS BATCH LAUNCHER (JDBC TYPE 2)
//********************************************
//STEP2    EXEC PGM=JVMLDM21,REGION=0M,COND=EVEN,
//         PARM='/ com.ibm.bankofz.batch.Bnkstmt'
//STEPLIB  DD  DISP=SHR,DSN=&JZOSLIB
//         DD  DISP=SHR,DSN=DB2V13.SDSNEXIT
//         DD  DISP=SHR,DSN=DB2V13.SDSNLOAD
//         DD  DISP=SHR,DSN=DB2V13.SDSNLOD2
//DATECARD DD *
202606
//SORTCODE DD *
123456
//SYSPRINT DD   DSN=&&JAVRPT,DISP=(NEW,PASS),UNIT=SYSDA,
//              SPACE=(CYL,(5,5)),DCB=(RECFM=VBA,LRECL=125,BLKSIZE=0)
//STDOUT   DD   SYSOUT=*
//STDERR   DD   SYSOUT=*
//STDENV   DD   *,SYMBOLS=JCLONLY
. /etc/profile
export JAVA_HOME=/usr/lpp/java/J21.0_64
export DB2_HOME=/usr/lpp/db2d10/jdbc
export PATH=$JAVA_HOME/bin:$PATH
export LIBPATH=$DB2_HOME/lib:$LIBPATH
export CLASSPATH=&BANKZJAR/bankz-batch-stmt.jar:$DB2_HOME/classes/db2jcc4.jar
IJO="-Dbankz.db2.url=jdbc:db2os390: -Dbankz.db2.schema=BANKZ"
IJO="$IJO -Ddb2.jcc.ssid=DBD1 -Dfile.encoding=IBM-1047"
export IBM_JAVA_OPTIONS="$IJO"
/*
//********************************************
//* PARITY GATE: PL/I REPORT VS JAVA REPORT
//********************************************
//STEP3    EXEC PGM=JVMLDM21,REGION=0M,COND=EVEN,
//         PARM='/ com.ibm.bankofz.parity.ParityGate'
//STEPLIB  DD  DISP=SHR,DSN=&JZOSLIB
//LEGACY   DD  DSN=&&PLIRPT,DISP=(OLD,PASS)
//CANDIDATE DD DSN=&&JAVRPT,DISP=(OLD,PASS)
//STDOUT   DD   SYSOUT=*
//STDERR   DD   SYSOUT=*
//STDENV   DD   *,SYMBOLS=JCLONLY
. /etc/profile
export JAVA_HOME=/usr/lpp/java/J21.0_64
export PATH=$JAVA_HOME/bin:$PATH
export CLASSPATH=&BANKZJAR/bankz-parity-harness.jar
export JZOS_MAIN_ARGS="compare-reports //DD:LEGACY //DD:CANDIDATE"
/*
//********************************************
//* THE PL/I REPORT STAYS THE JOB'S PRINTED OUTPUT UNTIL CUTOVER
//********************************************
//PRINT    EXEC PGM=IEBGENER,COND=EVEN
//SYSPRINT DD   DUMMY
//SYSIN    DD   DUMMY
//SYSUT1   DD   DSN=&&PLIRPT,DISP=(OLD,DELETE)
//SYSUT2   DD   SYSOUT=*
