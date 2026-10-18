package com.ibm.bankofz.core;

import java.nio.file.Files;
import java.nio.file.Path;

/** Locates the repository root so tests can read frozen copybooks and fixtures in place. */
public final class RepoPaths {

    private RepoPaths() {
    }

    public static Path root() {
        String prop = System.getProperty("bankofz.repoRoot");
        Path p = prop != null ? Path.of(prop) : Path.of("").toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("src/base/cics/copy"))) {
            p = p.getParent();
        }
        if (p == null) {
            throw new IllegalStateException("cannot locate Bank-of-Z repository root");
        }
        return p;
    }

    public static Path resolve(String relative) {
        return root().resolve(relative);
    }
}
