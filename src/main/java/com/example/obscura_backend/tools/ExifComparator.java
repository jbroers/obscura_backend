package com.example.obscura_backend.tools;

import com.example.obscura_backend.service.exif.ExifExtractionService;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

public class ExifComparator {
    private static final List<String> KEYS = Arrays.asList("Make","Model","Lens","ISO","Aperture","ShutterSpeed","FocalLength","Resolution","DateTaken","Orientation");

    public static void main(String[] args) throws Exception {
        Map<String,String> opts = parseArgs(args);
        Path debugDir = Paths.get(opts.getOrDefault("debug-dir","tools/debug_samples_compare"));
        String exiftoolPath = opts.get("exiftool-path");
        Path outJson = Paths.get(opts.getOrDefault("out","tools/debug_compare.json"));

        if (!Files.exists(debugDir) || !Files.isDirectory(debugDir)) {
            System.err.println("Debug directory not found: " + debugDir.toAbsolutePath());
            System.exit(1);
        }

        ExifExtractionService ex = new ExifExtractionService();

        List<Map<String,Object>> results = new ArrayList<>();

        try (var dirs = Files.list(debugDir)) {
            for (Path extDir : dirs.collect(Collectors.toList())) {
                if (!Files.isDirectory(extDir)) continue;
                Optional<Path> sampleOpt = Files.list(extDir).filter(p -> Files.isRegularFile(p) && !p.getFileName().toString().startsWith("exiftool")).findFirst();
                if (sampleOpt.isEmpty()) continue;
                Path sample = sampleOpt.get();

                byte[] bytes = Files.readAllBytes(sample);
                com.example.obscura_backend.tools.SimpleMultipartFile mm = new com.example.obscura_backend.tools.SimpleMultipartFile("file", sample.getFileName().toString(), "application/octet-stream", bytes);
                Map<String,String> base = ex.collectMetadataFromFile(mm);
                Map<String,String> ourCommon = ex.extractCommonFieldsFromMap(base);

                Map<String,Object> exiftoolMap = new LinkedHashMap<>();
                Path exiftoolTxt = extDir.resolve("exiftool.txt");
                if (exiftoolPath != null && !exiftoolPath.isEmpty()) {
                    try {
                        ProcessBuilder pb = new ProcessBuilder(exiftoolPath, "-j", sample.toAbsolutePath().toString());
                        pb.redirectErrorStream(true);
                        Process p = pb.start();
                        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                            String out = r.lines().collect(Collectors.joining(System.lineSeparator()));
                            if (out != null && !out.trim().isEmpty()) {
                                try {
                                    String trimmed = out.trim();
                                    if (trimmed.startsWith("[")) {
                                        int start = trimmed.indexOf('{');
                                        int end = trimmed.lastIndexOf('}');
                                        if (start >= 0 && end > start) {
                                            String obj = trimmed.substring(start, end+1);
                                            Map<String,Object> parsed = parseExiftoolJsonObject(obj);
                                            exiftoolMap.putAll(parsed);
                                        }
                                    }
                                } catch (Exception e) {
                                }
                            }
                        }
                    } catch (Exception e) {
                    }
                }

                if (exiftoolMap.isEmpty() && Files.exists(exiftoolTxt)) {
                    List<String> lines = Files.readAllLines(exiftoolTxt);
                    for (String L : lines) {
                        int idx = L.indexOf(':');
                        if (idx > 0) {
                            String key = L.substring(0, idx).trim();
                            String val = L.substring(idx+1).trim();
                            if (!val.isEmpty()) exiftoolMap.put(key, val);
                        }
                    }
                }

                Map<String,Object> fileResult = new LinkedHashMap<>();
                fileResult.put("extension", extDir.getFileName().toString());
                fileResult.put("filename", sample.getFileName().toString());
                Map<String,Object> perKey = new LinkedHashMap<>();

                for (String k : KEYS) {
                    String ourVal = ourCommon.getOrDefault(k, "");
                    boolean ourHas = ourVal != null && !ourVal.trim().isEmpty();
                    boolean exifHas = false;
                    String exifVal = "";
                    for (Map.Entry<String,Object> e : exiftoolMap.entrySet()) {
                        String ek = e.getKey().toLowerCase();
                        if (ek.contains(k.toLowerCase()) || ek.replaceAll("[^a-z]"," ").contains(k.toLowerCase())) {
                            String s = String.valueOf(e.getValue());
                            if (s != null && !s.trim().isEmpty()) { exifHas = true; exifVal = s; break; }
                        }
                    }

                    String status;
                    if (ourHas && exifHas) status = "BOTH";
                    else if (!ourHas && exifHas) status = "EXIF_ONLY";
                    else if (ourHas && !exifHas) status = "OURS_ONLY";
                    else status = "NEITHER";

                    Map<String,Object> kv = new LinkedHashMap<>();
                    kv.put("our", ourVal);
                    kv.put("exif", exifVal);
                    kv.put("status", status);
                    perKey.put(k, kv);
                }

                fileResult.put("keys", perKey);
                results.add(fileResult);
            }
        }

        String jsonOut = toJson(results);
        Files.createDirectories(outJson.getParent());
        Files.writeString(outJson, jsonOut);
        System.out.println("Wrote comparison to: " + outJson.toAbsolutePath());
        for (Map<String,Object> fr : results) {
            System.out.println(fr.get("extension") + ": " + fr.get("filename"));
            Map<String,Object> perKey = (Map<String,Object>) fr.get("keys");
            perKey.forEach((k,v) -> {
                @SuppressWarnings("unchecked") Map<String,Object> kv = (Map<String,Object>) v;
                String status = (String) kv.get("status");
                if (!status.equals("BOTH") && !status.equals("NEITHER")) {
                    System.out.println("  " + k + " -> " + status + " | our='" + kv.get("our") + "' exif='" + kv.get("exif") + "'");
                }
            });
        }
    }

    private static Map<String,Object> parseExiftoolJsonObject(String obj) {
        Map<String,Object> m = new LinkedHashMap<>();
        String s = obj.trim();
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("\\\"([^\\\\\"]+)\\\"\\s*:\\s*\\\"([^\\\\\"]*)\\\"");
        java.util.regex.Matcher mchr = p.matcher(s);
        while (mchr.find()) {
            String key = mchr.group(1);
            String val = mchr.group(2);
            if (val != null && !val.isEmpty()) m.put(key, val);
        }
        return m;
    }

    private static String toJson(Object o) {
        if (o == null) return "null";
        if (o instanceof Map) {
            @SuppressWarnings("unchecked") Map<String,Object> m = (Map<String,Object>) o;
            return "{" + m.entrySet().stream().map(e -> quote(e.getKey()) + ":" + toJson(e.getValue())).collect(Collectors.joining(",")) + "}";
        }
        if (o instanceof List) {
            @SuppressWarnings("unchecked") List<Object> l = (List<Object>) o;
            return "[" + l.stream().map(ExifComparator::toJson).collect(Collectors.joining(",")) + "]";
        }
        if (o instanceof String) return quote((String)o);
        return quote(String.valueOf(o));
    }

    private static String quote(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\\","\\\\").replace("\"","\\\"") + "\"";
    }

    private static Map<String,String> parseArgsRaw(String[] args) {
        Map<String,String> map = new HashMap<>();
        for (int i=0;i<args.length;i++) {
            String a = args[i];
            if (a.startsWith("--")) {
                String k = a.substring(2);
                String v = "";
                if (i+1 < args.length && !args[i+1].startsWith("--")) { v = args[i+1]; i++; }
                map.put(k,v);
            }
        }
        return map;
    }

    private static Map<String,String> parseArgs(String[] args) {
        return parseArgsRaw(args);
    }
}
