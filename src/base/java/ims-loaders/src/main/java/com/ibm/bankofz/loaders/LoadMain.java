package com.ibm.bankofz.loaders;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code LoadMain <LOADACCT|LOADCUSA|LOADCUST|LOADHIST|LOADTSTA> [--unload <file>]}.
 * Input is the program's DD (ACCTIN, CUSAIN, ...): {@code //DD:} through JZOS on z/OS, otherwise the
 * file in {@code -Dbankz.dd.<DD>}. Default target is DL/I through PSB IBLOAD ({@link DliSink});
 * {@code --unload} writes segment images instead.
 */
public final class LoadMain {
    private LoadMain() {
    }

    public static void main(String[] args) {
        int rc;
        try {
            rc = run(args);
        } catch (Exception e) {
            System.err.println("LOADMAIN: " + e);
            rc = 12;
        }
        System.exit(rc);
    }

    static int run(String... args) throws Exception {
        LoaderSpec spec = args.length > 0 ? Loaders.ALL.get(args[0]) : null;
        if (spec == null || (args.length != 1 && !(args.length == 3 && args[1].equals("--unload")))) {
            System.err.println("usage: LoadMain <" + String.join("|", Loaders.ALL.keySet().stream().sorted().toList())
                    + "> [--unload <file>]");
            return 12;
        }
        List<String> input = read(spec.inputDd());
        try (SegmentSink sink = args.length == 3
                ? new UnloadSink(new BufferedOutputStream(Files.newOutputStream(Path.of(args[2]))))
                : new DliSink(System.getProperty("bankz.ims.psb", "IBLOAD"))) {
            Loader.run(spec, input.iterator(), sink, System.out);
        }
        return 0;
    }

    static List<String> read(String dd) throws IOException {
        if ("z/OS".equals(System.getProperty("os.name"))) {
            return Zos.records(dd);
        }
        String path = System.getProperty("bankz.dd." + dd);
        if (path == null) {
            throw new IOException("DD " + dd + " not allocated (set -Dbankz.dd." + dd + "=<file>)");
        }
        return Files.readAllLines(Path.of(path), StandardCharsets.UTF_8);
    }

    /** Isolated so JZOS classes are only loaded on z/OS. */
    private static final class Zos {
        static List<String> records(String dd) throws IOException {
            com.ibm.jzos.ZFile f = new com.ibm.jzos.ZFile("//DD:" + dd, "rb,type=record,noseek");
            try {
                List<String> out = new ArrayList<>();
                byte[] buf = new byte[f.getLrecl()];
                int n;
                while ((n = f.read(buf)) >= 0) {
                    out.add(new String(buf, 0, n, com.ibm.bankofz.bridge.runtime.Ebcdic.CP1047));
                }
                return out;
            } finally {
                f.close();
            }
        }
    }
}
