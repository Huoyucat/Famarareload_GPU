package com.wenkrang.famara;

import java.nio.file.Path;

/** Root for optional Paper integration harnesses; defaults to the checkout directory. */
public final class TestPaths {
    private TestPaths() {}
    public static final Path ROOT = Path.of(System.getProperty("famara.test.root", ".")).toAbsolutePath().normalize();
}
