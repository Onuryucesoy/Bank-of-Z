package com.ibm.bankofz.batch;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * DD access. Under the JZOS launcher on z/OS, {@code //DD:name} through ZFile, in EBCDIC. Elsewhere (tests,
 * development) the DD is the file named by system property {@code bankz.dd.<NAME>}; SYSPRINT defaults to
 * stdout. A missing DD is an error (PL/I UNDEFINEDFILE); an empty one is ENDFILE.
 */
final class Dd {
    static final boolean ZOS = "z/OS".equals(System.getProperty("os.name"));
    static final Charset EBCDIC = Charset.forName("IBM1047");

    private Dd() {
    }

    /** First record of a card-image DD, or null at ENDFILE. */
    static String firstRecord(String dd) throws IOException {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(open(dd),
                ZOS ? EBCDIC : StandardCharsets.UTF_8))) {
            return r.readLine();
        }
    }

    static Writer writer(String dd) throws IOException {
        if (ZOS) {
            return new OutputStreamWriter(Zos.output(dd), EBCDIC);
        }
        String path = System.getProperty("bankz.dd." + dd);
        OutputStream os = path == null ? System.out : Files.newOutputStream(Path.of(path));
        return new OutputStreamWriter(os, StandardCharsets.UTF_8);
    }

    private static InputStream open(String dd) throws IOException {
        if (ZOS) {
            return Zos.input(dd);
        }
        String path = System.getProperty("bankz.dd." + dd);
        if (path == null) {
            throw new IOException("DD " + dd + " not allocated (set -Dbankz.dd." + dd + "=<file>)");
        }
        return Files.newInputStream(Path.of(path));
    }

    /** Isolated so JZOS classes are only loaded on z/OS. */
    private static final class Zos {
        static InputStream input(String dd) throws IOException {
            if (!com.ibm.jzos.ZFile.ddExists(dd)) {
                throw new IOException("DD " + dd + " not allocated");
            }
            return new com.ibm.jzos.ZFile("//DD:" + dd, "rt").getInputStream();
        }

        static OutputStream output(String dd) throws IOException {
            return new com.ibm.jzos.ZFile("//DD:" + dd, "wt").getOutputStream();
        }
    }
}
