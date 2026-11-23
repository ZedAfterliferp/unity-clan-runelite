package com.unityclan;

import net.runelite.client.util.Filepath;
import java.io.IOException;

public final class ClanCache {
    private static volatile Filepath directory;
    private ClanCache() {}
    public static void initialize(Filepath path) { directory = path; }
    public static Filepath file(String name) { return directory.joinSegment(name); }
    public static long modified(Filepath file) {
        try { return file.getLastModifiedTime().toMillis(); }
        catch (IOException ex) { return 0L; }
    }
}
