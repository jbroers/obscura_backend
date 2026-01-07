package com.example.obscura_backend.integration;

import com.example.obscura_backend.service.exif.ExifExtractionService;
import com.example.obscura_backend.service.raw.RawImageExtractionService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class RawFormatProbeTest {

    private static final Path SAMPLES_DIR = Paths.get("src", "test", "resources", "raw", "raws");

    @Test
    public void probeAllExtensionsUsingServices() throws Exception {
        if (!Files.exists(SAMPLES_DIR)) return;

        ExifExtractionService exifService = new ExifExtractionService();
        RawImageExtractionService rawService = new RawImageExtractionService();

        Map<String, List<Path>> byExt = new HashMap<>();
        List<Path> skippedFiles = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.walk(SAMPLES_DIR)) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                String ext = getExt(p.getFileName().toString());
                if (ext == null) ext = "";
                byExt.computeIfAbsent(ext, k -> new ArrayList<>()).add(p);
            });
        }

        List<String> extOrder = new ArrayList<>(byExt.keySet());
        Collections.sort(extOrder);

        File reportFile = new File("build/test-results/raw-format-probe-services.txt");
        File parent = reportFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("Could not create report directory: " + parent.getAbsolutePath());
        }

        String[] keys = new String[]{"Make","Model","Lens","ISO","Aperture","ShutterSpeed","FocalLength","Resolution","DateTaken","Orientation"};

        try (FileWriter fw = new FileWriter(reportFile, false)) {
            fw.write("Raw format probe (using services)\n");

            for (String ext : extOrder) {
                fw.write("\n=== Extension: " + (ext.isEmpty() ? "(none)" : ext) + " ===\n");
                List<Path> files = byExt.get(ext);

                for (Path p : files) {
                    fw.write("\nFile: " + p.getFileName() + "\n");

                    byte[] bytes = Files.readAllBytes(p);
                    String contentType = Files.probeContentType(p);
                    MockMultipartFile mm = new MockMultipartFile("file", p.getFileName().toString(), contentType == null ? "application/octet-stream" : contentType, bytes);

                    Map<String,String> topMap = exifService.collectMetadataFromFile(mm);
                    Map<String,String> topCommon = exifService.extractCommonFieldsFromMap(topMap);

                    RawImageExtractionService.PreviewResult pr = rawService.extractRawPreviewWithMetadata(mm);
                    Map<String,String> embeddedCommon = new LinkedHashMap<>();
                    if (pr != null && pr.metadata != null) {
                        Map<String,String> emb = exifService.collectMetadata(pr.metadata);
                        embeddedCommon = exifService.extractCommonFieldsFromMap(emb);
                    }

                    Map<String,String> embeddedOnlyMeta = new LinkedHashMap<>();
                    try {
                        com.drew.metadata.Metadata embOnly = rawService.extractMetadataFromEmbeddedJpeg(mm);
                        if (embOnly != null) embeddedOnlyMeta = exifService.extractCommonFieldsFromMap(exifService.collectMetadata(embOnly));
                    } catch (Exception ignored) {}

                    Map<String,String> exiftoolMap = new LinkedHashMap<>();
                    try {
                        Map<String,String> exifm = rawService.extractMetadataWithExiftool(mm);
                        if (exifm != null) exiftoolMap = exifm;
                    } catch (Exception ignored) {}
                    Map<String,String> exiftoolCommon = exifService.extractCommonFieldsFromMap(exiftoolMap);

                    fw.write("  Findings per source:\n");
                    writeFoundLine(fw, "    topMap", topCommon, keys);
                    writeFoundLine(fw, "    raw-embedded", embeddedCommon, keys);
                    writeFoundLine(fw, "    embeddedOnly", embeddedOnlyMeta, keys);
                    writeFoundLine(fw, "    exiftool", exiftoolCommon, keys);

                    Map<String, Boolean> anyFound = new LinkedHashMap<>();
                    for (String k : keys) anyFound.put(k, false);
                    for (String k : keys) {
                        if (notEmpty(topCommon.get(k)) || notEmpty(embeddedCommon.get(k)) || notEmpty(embeddedOnlyMeta.get(k)) || notEmpty(exiftoolCommon.get(k))) {
                            anyFound.put(k, true);
                        }
                    }

                    fw.write("\n  Aggregate for file:\n");
                    for (String k : keys) fw.write(String.format("    %-12s : %s\n", k, anyFound.get(k) ? "FOUND" : "MISSING"));

                }

                Map<String, Boolean> extFound = new LinkedHashMap<>();
                Map<String, List<Path>> missingExamples = new LinkedHashMap<>();
                for (String k : keys) {
                    extFound.put(k, false);
                    missingExamples.put(k, new ArrayList<>());
                }

                for (Path p : files) {
                    byte[] bytes = Files.readAllBytes(p);
                    MockMultipartFile mm = new MockMultipartFile("file", p.getFileName().toString(), "application/octet-stream", bytes);
                    Map<String,String> topMap = exifService.collectMetadataFromFile(mm);
                    Map<String,String> topCommon = exifService.extractCommonFieldsFromMap(topMap);
                    RawImageExtractionService.PreviewResult pr = rawService.extractRawPreviewWithMetadata(mm);
                    Map<String,String> embeddedCommon = new LinkedHashMap<>();
                    if (pr != null && pr.metadata != null) embeddedCommon = exifService.extractCommonFieldsFromMap(exifService.collectMetadata(pr.metadata));
                    Map<String,String> embeddedOnlyMeta = new LinkedHashMap<>();
                    try { com.drew.metadata.Metadata embOnly = rawService.extractMetadataFromEmbeddedJpeg(mm); if (embOnly != null) embeddedOnlyMeta = exifService.extractCommonFieldsFromMap(exifService.collectMetadata(embOnly)); } catch (Exception ignored) {}
                    Map<String,String> exiftoolMap = new LinkedHashMap<>();
                    try { Map<String,String> exifm = rawService.extractMetadataWithExiftool(mm); if (exifm != null) exiftoolMap = exifm; } catch (Exception ignored) {}
                    Map<String,String> exiftoolCommon = exifService.extractCommonFieldsFromMap(exiftoolMap);

                    for (String k : keys) {
                        boolean found = notEmpty(topCommon.get(k)) || notEmpty(embeddedCommon.get(k)) || notEmpty(embeddedOnlyMeta.get(k)) || notEmpty(exiftoolCommon.get(k));
                        if (found) {
                            extFound.put(k, true);
                        } else {
                            List<Path> le = missingExamples.get(k);
                            if (le.size() < 5) le.add(p);
                        }
                    }
                }

                fw.write("\nExtension summary for '" + ext + "':\n");
                for (String k : keys) fw.write(String.format("  %-12s : %s\n", k, extFound.get(k) ? "FOUND" : "MISSING"));

                fw.write("\nExample files missing keys (up to 5 per key):\n");
                for (String k : keys) {
                    List<Path> le = missingExamples.get(k);
                    if (le != null && !le.isEmpty()) {
                        fw.write("  " + k + " :");
                        for (Path p : le) fw.write(" " + p.getFileName());
                        fw.write("\n");
                    }
                }
            }

            if (!skippedFiles.isEmpty()) {
                fw.write("\nSkipped files (non-image/unknown extensions):\n");
                Map<String, Long> skipByExt = skippedFiles.stream().collect(Collectors.groupingBy(p -> getExt(p.getFileName().toString()), Collectors.counting()));
                for (Map.Entry<String, Long> e : skipByExt.entrySet()) fw.write(String.format("  %s : %d\n", e.getKey(), e.getValue()));
                fw.write("Detailed skipped files:\n");
                for (Path p : skippedFiles) fw.write("  " + p.toString() + "\n");
            }

            fw.flush();
        }

    }

    private void writeFoundLine(FileWriter fw, String label, Map<String,String> common, String[] keys) throws Exception {
        fw.write(label + ":\n");
        for (String k : keys) {
            fw.write(String.format("      %-12s : %s\n", k, notEmpty(common.get(k)) ? common.get(k) : "-"));
        }
    }

    private static String getExt(String name) {
        int i = name.lastIndexOf('.');
        if (i < 0) return "";
        return name.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
