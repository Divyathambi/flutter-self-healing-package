package com.flutterImpactAnalyzer.healing;

import java.nio.file.Files;
import java.nio.file.Path;

/** A portable subprocess fixture; no shell, Flutter SDK, or Git repository needed. */
public final class PreviewProcessFixture {
    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "ok":
                System.out.print("preview output");
                System.err.print("diagnostic output");
                break;
            case "large":
                System.out.print("x".repeat(128 * 1024));
                break;
            case "sleep":
                Thread.sleep(30_000);
                break;
            case "child":
                String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
                Process child = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                        PreviewProcessFixture.class.getName(), "sleep").start();
                Files.writeString(Path.of(args[1]), Long.toString(child.pid()));
                Thread.sleep(30_000);
                break;
            default:
                throw new IllegalArgumentException("Unknown fixture mode");
        }
    }
}
