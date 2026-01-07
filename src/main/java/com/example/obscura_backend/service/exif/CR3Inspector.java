package com.example.obscura_backend.service.exif;

import java.io.File;
import java.nio.file.Files;
import java.util.Map;

/**
 * Small CLI helper to inspect a local CR3 file using the CR3Parser.
 * Usage: run this from your IDE or with Gradle by invoking the class's main.
 */
public class CR3Inspector {

    public static void main(String[] args) throws Exception {
        if (args == null || args.length == 0) {
            System.err.println("Usage: CR3Inspector <path-to-file>");
            return;
        }
        File f = new File(args[0]);
        if (!f.exists()) {
            System.err.println("File does not exist: " + f.getAbsolutePath());
            return;
        }

        byte[] data = Files.readAllBytes(f.toPath());
        Map<String, String> map = CR3Parser.parseBytes(data, f.getName());
        System.out.println("CR3Parser returned " + map.size() + " entries:");
        map.forEach((k,v) -> System.out.println(k + " = " + v));
    }
}
