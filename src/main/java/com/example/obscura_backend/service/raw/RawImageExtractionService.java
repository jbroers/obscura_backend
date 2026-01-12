package com.example.obscura_backend.service.raw;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.Tag;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class RawImageExtractionService {

    private static final Logger logger = LoggerFactory.getLogger(RawImageExtractionService.class);

    public static class PreviewResult {
        public final BufferedImage preview;
        public final Metadata metadata;

        public PreviewResult(BufferedImage preview, Metadata metadata) {
            this.preview = preview;
            this.metadata = metadata;
        }
    }

    public PreviewResult extractRawPreviewWithMetadata(MultipartFile file) {
        BufferedImage image;
        Metadata foundMetadata = null;

        try (InputStream inputStream = file.getInputStream();
             BufferedInputStream bufferedStream = new BufferedInputStream(inputStream)) {

            image = ImageIO.read(bufferedStream);

            if (image != null && image.getWidth() > 0 && image.getHeight() > 0) {
                logger.debug("Loaded RAW with ImageIO: {}x{} pixels", image.getWidth(), image.getHeight());
                try (InputStream metaStream = file.getInputStream()) {
                    foundMetadata = ImageMetadataReader.readMetadata(metaStream);
                } catch (Exception ignored) {
                }
                return new PreviewResult(image, foundMetadata);
            }
        } catch (Exception e) {
            logger.debug("ImageIO could not read RAW file: {}", e.getMessage());
        }

        try {
            image = extractMetadataPreview(file);
            if (image != null) {
                try (InputStream metaStream = file.getInputStream()) {
                    foundMetadata = ImageMetadataReader.readMetadata(metaStream);
                } catch (Exception ignored) {
                }
                logger.info("V Extracted metadata preview: {}x{} pixels", image.getWidth(), image.getHeight());
                return new PreviewResult(image, foundMetadata);
            }
        } catch (Exception e) {
            logger.debug("Could not extract metadata preview: {}", e.getMessage());
        }

        try {
            image = tryReadWithCommonsImaging(file);
            if (image != null) {
                try (InputStream metaStream = file.getInputStream()) {
                    foundMetadata = ImageMetadataReader.readMetadata(metaStream);
                } catch (Exception ignored) {}
                logger.info("CommonsImaging decoded RAW/DNG: {}x{}", image.getWidth(), image.getHeight());
                return new PreviewResult(image, foundMetadata);
            }
        } catch (Exception e) {
            logger.debug("CommonsImaging DNG decode failed: {}", e.getMessage());
        }

        try {
            byte[] fileBytes = file.getBytes();
            int searchStartIdx = 0;
            int jpegCount = 0;
            List<BufferedImage> foundImages = new ArrayList<>();
            int decodedCount = 0;
            int maxDecodedToTry = 3;

            while (searchStartIdx < fileBytes.length - 2) {
                int startIdx = -1;
                for (int i = searchStartIdx; i < fileBytes.length - 2; i++) {
                    if ((fileBytes[i] & 0xFF) == 0xFF && (fileBytes[i + 1] & 0xFF) == 0xD8) {
                        startIdx = i;
                        break;
                    }
                }
                if (startIdx == -1) break;

                int endIdx = -1;
                for (int i = startIdx + 2; i < fileBytes.length - 1; i++) {
                    if ((fileBytes[i] & 0xFF) == 0xFF && (fileBytes[i + 1] & 0xFF) == 0xD9) {
                        endIdx = i + 2;
                        break;
                    }
                }
                if (endIdx == -1) { searchStartIdx = startIdx + 2; continue; }

                int jpegLength = endIdx - startIdx;
                jpegCount++;

                if (jpegLength > 10000) {
                    byte[] jpegBytes = new byte[jpegLength];
                    System.arraycopy(fileBytes, startIdx, jpegBytes, 0, jpegLength);

                    try (ByteArrayInputStream bais = new ByteArrayInputStream(jpegBytes)) {
                        Metadata m = ImageMetadataReader.readMetadata(bais);
                        if (m != null && (foundMetadata == null)) {
                            foundMetadata = m;
                            logger.debug("Extracted metadata from embedded JPEG #{} ({} KB)", jpegCount, jpegLength / 1024);
                        }
                    } catch (Exception e) {
                        logger.debug("Failed to read metadata from embedded JPEG #{}: {}", jpegCount, e.getMessage());
                    }

                    if (decodedCount < maxDecodedToTry) {
                        BufferedImage dec = tryDecodeWithRepair(jpegBytes, jpegCount);
                        if (dec != null) {
                            foundImages.add(dec);
                            decodedCount++;
                        }
                    }

                    if (foundMetadata != null && hasDateLike(foundMetadata) && !foundImages.isEmpty()) {
                        break;
                    }
                }

                searchStartIdx = endIdx;
            }

            if (!foundImages.isEmpty()) {
                BufferedImage largest = foundImages.stream()
                        .max((img1, img2) -> Integer.compare(img1.getWidth() * img1.getHeight(), img2.getWidth() * img2.getHeight()))
                        .orElse(null);
                if (largest != null) {
                    logger.info("Selected largest: {}x{} pixels", largest.getWidth(), largest.getHeight());
                    return new PreviewResult(largest, foundMetadata);
                }
            }

        } catch (Exception e) {
            logger.debug("Embedded JPEG scan failed: {}", e.getMessage());
        }

        return new PreviewResult(null, foundMetadata);
    }

    private boolean hasDateLike(Metadata m) {
        if (m == null) return false;
        for (Directory d : m.getDirectories()) {
            for (Tag t : d.getTags()) {
                String name = t.getTagName();
                String desc = t.getDescription();
                if ((name != null && (name.toLowerCase().contains("date") || name.toLowerCase().contains("time"))) ||
                        (desc != null && desc.matches(".*\\d{4}.*"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private BufferedImage extractMetadataPreview(MultipartFile file) throws IOException {
        try (InputStream inputStream = file.getInputStream()) {
            Metadata metadata = ImageMetadataReader.readMetadata(inputStream);

            logger.debug("Searching for RAW preview in metadata directories...");

            for (Directory directory : metadata.getDirectories()) {
                String dirName = directory.getName();

                if (dirName.contains("Preview") || dirName.contains("Thumbnail") ||
                        dirName.contains("JPEG") || dirName.contains("Makernote") ||
                        dirName.contains("SubIFD") || dirName.contains("IFD")) {

                    for (Tag tag : directory.getTags()) {
                        String tagName = tag.getTagName();

                        if (tagName == null) continue;
                        String tn = tagName.toLowerCase();
                        if (tn.contains("preview") || tn.contains("thumbnail") || tn.contains("jpgfromraw") || tn.contains("previewimage") || tn.contains("jpeg") || tn.contains("data") || tn.contains("offset")) {
                            try {
                                byte[] imageBytes = null;
                                try { imageBytes = directory.getByteArray(tag.getTagType()); } catch (Exception ignored) {}
                                if (imageBytes == null) {
                                    try { Object raw = directory.getObject(tag.getTagType()); if (raw instanceof byte[]) imageBytes = (byte[]) raw; } catch (Exception ignored) {}
                                }

                                if (imageBytes != null && imageBytes.length > 1000) {
                                    try (ByteArrayInputStream bais = new ByteArrayInputStream(imageBytes)) {
                                        BufferedImage preview = ImageIO.read(bais);
                                        if (preview != null && preview.getWidth() > 100) {
                                            logger.info("Found preview in {} / {}: {}x{}", dirName, tagName, preview.getWidth(), preview.getHeight());
                                            return preview;
                                        }
                                    } catch (Exception e) {
                                        logger.debug("Could not decode preview bytes from '{}': {}", tagName, e.getMessage());
                                    }
                                }
                            } catch (Exception e) {
                                logger.debug("Could not decode from '{}': {}", tagName, e.getMessage());
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Metadata preview extraction failed: {}", e.getMessage());
        }
        try {
            BufferedImage viaTiff = tryReadWithImageIOAsTiff(file);
            if (viaTiff != null) return viaTiff;
        } catch (Exception e) {
            logger.debug("TIFF/ImageIO DNG read fallback failed: {}", e.getMessage());
        }
        return null;
    }

    private BufferedImage tryReadWithImageIOAsTiff(MultipartFile file) {
        try {
            byte[] bytes = file.getBytes();
            javax.imageio.stream.ImageInputStream iis = null;
            java.util.Iterator<javax.imageio.ImageReader> readers = javax.imageio.ImageIO.getImageReadersByFormatName("tiff");
            if (readers == null || !readers.hasNext()) readers = javax.imageio.ImageIO.getImageReaders(new java.io.ByteArrayInputStream(bytes));

            if (readers == null) return null;

            while (readers.hasNext()) {
                javax.imageio.ImageReader reader = readers.next();
                try (InputStream bais = new ByteArrayInputStream(bytes)) {
                    iis = javax.imageio.ImageIO.createImageInputStream(bais);
                    if (iis == null) continue;
                    reader.setInput(iis, true, true);

                    int numImages = 1;
                    try {
                        numImages = reader.getNumImages(true);
                    } catch (Exception ignored) {}

                    for (int idx = 0; idx < numImages; idx++) {
                        try {
                            BufferedImage img = reader.read(idx);
                            if (img != null && img.getWidth() > 0) {
                                logger.info("ImageIO reader '{}' decoded DNG/TIFF (index {}): {}x{}", reader.getFormatName(), idx, img.getWidth(), img.getHeight());
                                return img;
                            }
                        } catch (IndexOutOfBoundsException iobe) {
                            break;
                        } catch (Exception e) {
                            logger.debug("ImageIO reader {} failed to read index {}: {}", reader.getFormatName(), idx, e.getMessage());
                        }
                    }
                } catch (Exception e) {
                    logger.debug("ImageIO reader {} initialization failed: {}", reader.getFormatName(), e.getMessage());
                } finally {
                    try { reader.dispose(); } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            logger.debug("tryReadWithImageIOAsTiff failed: {}", e.getMessage());
        }
        return null;
    }

    private BufferedImage findEmbeddedJpegInBytes(MultipartFile file) throws IOException {
        byte[] fileBytes = file.getBytes();

        logger.info("Scanning {} KB for embedded JPEG...", fileBytes.length / 1024);

        List<BufferedImage> foundImages = new ArrayList<>();
        int searchStartIdx = 0;
        int jpegCount = 0;

        while (searchStartIdx < fileBytes.length - 2) {

            int startIdx = -1;
            for (int i = searchStartIdx; i < fileBytes.length - 2; i++) {
                if ((fileBytes[i] & 0xFF) == 0xFF && (fileBytes[i + 1] & 0xFF) == 0xD8) {
                    startIdx = i;
                    break;
                }
            }

            if (startIdx == -1) break;

            int endIdx = -1;
            for (int i = startIdx + 2; i < fileBytes.length - 1; i++) {
                if ((fileBytes[i] & 0xFF) == 0xFF && (fileBytes[i + 1] & 0xFF) == 0xD9) {
                    endIdx = i + 2;
                    break;
                }
            }

            if (endIdx == -1) {
                searchStartIdx = startIdx + 2;
                continue;
            }

            int jpegLength = endIdx - startIdx;
            jpegCount++;

            if (jpegLength > 10000) {
                logger.debug("Found JPEG #{} at offset {}: {} KB", jpegCount, startIdx, jpegLength / 1024);

                byte[] jpegBytes = new byte[jpegLength];
                System.arraycopy(fileBytes, startIdx, jpegBytes, 0, jpegLength);

                BufferedImage image = tryDecodeWithRepair(jpegBytes, jpegCount);
                if (image != null) {
                    foundImages.add(image);
                }
            }

            searchStartIdx = endIdx;
        }

        logger.debug("Scan complete: {} JPEG(s) found, {} decoded", jpegCount, foundImages.size());

        if (!foundImages.isEmpty()) {
            BufferedImage largest = foundImages.stream()
                    .max((img1, img2) -> Integer.compare(
                            img1.getWidth() * img1.getHeight(),
                            img2.getWidth() * img2.getHeight()))
                    .orElse(null);

            if (largest != null) {
                logger.info("Selected largest: {}x{} pixels", largest.getWidth(), largest.getHeight());
                return largest;
            }
        }

        return null;
    }

    public Metadata extractMetadataFromEmbeddedJpeg(MultipartFile file) {
        try {
            byte[] fileBytes = file.getBytes();
            int searchStartIdx = 0;
            int jpegCount = 0;

            while (searchStartIdx < fileBytes.length - 2) {
                int startIdx = -1;
                for (int i = searchStartIdx; i < fileBytes.length - 2; i++) {
                    if ((fileBytes[i] & 0xFF) == 0xFF && (fileBytes[i + 1] & 0xFF) == 0xD8) {
                        startIdx = i;
                        break;
                    }
                }
                if (startIdx == -1) break;

                int endIdx = -1;
                for (int i = startIdx + 2; i < fileBytes.length - 1; i++) {
                    if ((fileBytes[i] & 0xFF) == 0xFF && (fileBytes[i + 1] & 0xFF) == 0xD9) {
                        endIdx = i + 2;
                        break;
                    }
                }
                if (endIdx == -1) {
                    searchStartIdx = startIdx + 2;
                    continue;
                }

                int jpegLength = endIdx - startIdx;
                jpegCount++;

                if (jpegLength > 10000) {
                    byte[] jpegBytes = new byte[jpegLength];
                    System.arraycopy(fileBytes, startIdx, jpegBytes, 0, jpegLength);
                    try (ByteArrayInputStream bais = new ByteArrayInputStream(jpegBytes)) {
                        Metadata m = ImageMetadataReader.readMetadata(bais);
                        if (m != null) {
                            logger.info("Extracted metadata from embedded JPEG #{} ({} KB)", jpegCount, jpegLength / 1024);
                            return m;
                        }
                    } catch (Exception e) {
                        logger.debug("Failed to read metadata from embedded JPEG #{}: {}", jpegCount, e.getMessage());
                    }
                }

                searchStartIdx = endIdx;
            }
        } catch (Exception e) {
            logger.debug("extractMetadataFromEmbeddedJpeg failed: {}", e.getMessage());
        }

        try {
            BufferedImage dcrawPreview = extractWithDcraw(file);
            if (dcrawPreview != null) {
                try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                    ImageIO.write(dcrawPreview, "jpg", baos);
                    try (ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray())) {
                        Metadata m = ImageMetadataReader.readMetadata(bais);
                        if (m != null) {
                            logger.info("Extracted metadata via dcraw-produced preview");
                            return m;
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("dcraw metadata extraction fallback failed: {}", e.getMessage());
        }

        return null;
    }

    private BufferedImage tryDecodeWithRepair(byte[] jpegBytes, int jpegNumber) {

        try (ByteArrayInputStream bais = new ByteArrayInputStream(jpegBytes)) {
            BufferedImage image = ImageIO.read(bais);
            if (image != null && image.getWidth() > 0) {
                logger.debug("  V Decoded JPEG #{} with ImageIO: {}x{}", jpegNumber, image.getWidth(), image.getHeight());
                return image;
            }
        } catch (Exception e) {
            logger.debug("  ✗ ImageIO failed: {}", e.getMessage());
        }

        try {
            Path tempFile = Files.createTempFile("dng_jpeg_", ".jpg");
            try {
                Files.write(tempFile, jpegBytes);
                BufferedImage image = ImageIO.read(tempFile.toFile());
                if (image != null && image.getWidth() > 0) {
                    logger.debug("  V Decoded JPEG #{} via temp file: {}x{}", jpegNumber, image.getWidth(), image.getHeight());
                    return image;
                }
            } finally {
                Files.deleteIfExists(tempFile);
            }
        } catch (Exception e) {
            logger.debug("  ✗ Temp file failed: {}", e.getMessage());
        }

        logger.debug("  ✗ All decode methods failed for JPEG #{} (likely corrupted or proprietary format)", jpegNumber);
        return null;
    }

    private BufferedImage extractWithDcraw(MultipartFile file) {
        if (!isDcrawAvailable()) {
            logger.debug("dcraw is not available on system PATH");
            return null;
        }

        Path tempDng = null;
        Path tempJpg = null;

        try {
            tempDng = Files.createTempFile("obscura_raw_", ".dng");
            Files.write(tempDng, file.getBytes());

            tempJpg = Files.createTempFile("obscura_preview_", ".jpg");

            ProcessBuilder pb = new ProcessBuilder(
                "dcraw",
                "-e",
                    "-c",
                    tempDng.toString()
            );

            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (InputStream processOutput = process.getInputStream();
                 OutputStream fileOutput = Files.newOutputStream(tempJpg)) {

                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = processOutput.read(buffer)) != -1) {
                    fileOutput.write(buffer, 0, bytesRead);
                }
            }

            int exitCode = process.waitFor();

            if (exitCode == 0) {
                if (Files.exists(tempJpg) && Files.size(tempJpg) > 1000) {
                    BufferedImage image = ImageIO.read(tempJpg.toFile());
                    if (image != null && image.getWidth() > 0) {
                        logger.info("dcraw successfully extracted preview: {}x{} pixels",
                                image.getWidth(), image.getHeight());
                        return image;
                    }
                }
            } else {
                logger.debug("dcraw -e returned code {} (trying other dcraw modes)", exitCode);
            }

            List<String[]> cmds = new ArrayList<>();
            cmds.add(new String[]{"dcraw", "-c", "-w", "-q", "3", tempDng.toString()});
            cmds.add(new String[]{"dcraw", "-c", "-h", "-w", "-q", "3", tempDng.toString()});
            cmds.add(new String[]{"dcraw", "-T", "-w", "-q", "3", tempDng.toString()});

            for (String[] cmd : cmds) {
                try {
                    ProcessBuilder pb2 = new ProcessBuilder(cmd);
                    pb2.redirectErrorStream(true);
                    Process p2 = pb2.start();

                    try (InputStream out = p2.getInputStream()) {
                        BufferedImage img = ImageIO.read(out);
                        int code = p2.waitFor();
                        if (img != null && img.getWidth() > 0) {
                            logger.info("dcraw mode {} produced image: {}x{} (exit {})", String.join(" ", cmd), img.getWidth(), img.getHeight(), code);
                            return img;
                        } else {
                            logger.debug("dcraw mode {} did not yield a readable image (exit {})", String.join(" ", cmd), code);
                        }
                    } catch (Exception e) {
                        logger.debug("dcraw mode {} failed to read output: {}", String.join(" ", cmd), e.getMessage());
                    }
                } catch (Exception e) {
                    logger.debug("dcraw mode {} start failed: {}", String.join(" ", cmd), e.getMessage());
                }
            }

            try {
                Path tempTiff = Files.createTempFile("obscura_raw_", ".tiff");
                ProcessBuilder pb3 = new ProcessBuilder("dcraw", "-T", "-w", "-q", "3", "-O", tempTiff.toString(), tempDng.toString());
                pb3.redirectErrorStream(true);
                Process p3 = pb3.start();
                try (InputStream is3 = p3.getInputStream()) {
                    while (is3.read() != -1) {}
                }
                int rc3 = p3.waitFor();
                if (rc3 == 0 && Files.exists(tempTiff) && Files.size(tempTiff) > 1000) {
                    try {
                        BufferedImage ti = ImageIO.read(tempTiff.toFile());
                        if (ti != null && ti.getWidth() > 0) {
                            logger.info("dcraw -> TIFF succeeded: {}x{}", ti.getWidth(), ti.getHeight());
                            Files.deleteIfExists(tempTiff);
                            return ti;
                        }
                    } catch (Exception e) { logger.debug("Reading dcraw TIFF failed: {}", e.getMessage()); }
                }
                Files.deleteIfExists(tempTiff);
            } catch (Exception e) {
                logger.debug("dcraw -> TIFF attempt failed: {}", e.getMessage());
            }

        } catch (Exception e) {
            logger.debug("dcraw extraction failed: {}", e.getMessage());
        } finally {
            try {
                if (tempDng != null) Files.deleteIfExists(tempDng);
                if (tempJpg != null) Files.deleteIfExists(tempJpg);
            } catch (IOException e) {
                logger.debug("Could not cleanup temp files: {}", e.getMessage());
            }
        }

        return null;
    }

    private boolean isDcrawAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder("dcraw", "-h");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (InputStream is = process.getInputStream()) {
                while (is.read() != -1) {
                }
            }

            int exitCode = process.waitFor();

            boolean available = (exitCode == 0 || exitCode == 1);

            if (available) {
                logger.info("dcraw is available on system");
            }

            return available;

        } catch (Exception e) {
            logger.debug("dcraw not available: {}", e.getMessage());
            return false;
        }
    }

    public Map<String, String> extractMetadataWithExiftool(MultipartFile file) {
        if (!isExiftoolAvailable()) return null;

        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("obscura_raw_exiftool_", "");
            Files.write(tempFile, file.getBytes());

            ProcessBuilder pb = new ProcessBuilder("exiftool", "-json", "-a", "-G1", tempFile.toString());
            pb.redirectErrorStream(true);
            Process p = pb.start();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (InputStream is = p.getInputStream()) {
                byte[] buf = new byte[8192];
                int r;
                while ((r = is.read(buf)) != -1) baos.write(buf, 0, r);
            }

            int exit = p.waitFor();
            if (exit != 0) {
                logger.debug("exiftool exited with {}", exit);
                return null;
            }

            byte[] out = baos.toByteArray();
            if (out.length == 0) return null;

            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(out);
            if (!root.isArray() || root.isEmpty()) return null;

            JsonNode obj = root.get(0);
            Map<String, String> map = new HashMap<>();
            obj.fields().forEachRemaining(entry -> {
                String key = entry.getKey();
                String val = entry.getValue().isTextual() ? entry.getValue().asText() : entry.getValue().toString();
                map.put(key, val);
            });

            logger.info("exiftool extracted {} tags", map.size());
            return map;

        } catch (Exception e) {
            logger.debug("exiftool extraction failed: {}", e.getMessage());
            return null;
        } finally {
            try { if (tempFile != null) Files.deleteIfExists(tempFile); } catch (IOException ignored) {}
        }
    }

    private boolean isExiftoolAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder("exiftool", "-ver");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (InputStream is = p.getInputStream()) { while (is.read() != -1) {} }
            int exit = p.waitFor();
            return (exit == 0);
        } catch (Exception e) {
            logger.debug("exiftool not available: {}", e.getMessage());
            return false;
        }
    }

    private BufferedImage tryReadWithCommonsImaging(MultipartFile file) {
        try {
            byte[] bytes = file.getBytes();
            try {
                BufferedImage img = org.apache.commons.imaging.Imaging.getBufferedImage(bytes);
                if (img != null && img.getWidth() > 0) return img;
            } catch (Exception e) {
                logger.debug("CommonsImaging.getBufferedImage failed: {}", e.getMessage());
            }
        } catch (Exception e) {
            logger.debug("tryReadWithCommonsImaging failed: {}", e.getMessage());
        }
        return null;
    }

    public BufferedImage createPlaceholderImage(String filename) {
        final int w = 1024;
        final int h = 768;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(new Color(34, 34, 34));
            g.fillRect(0, 0, w, h);
            g.setColor(new Color(200, 200, 200));
            g.setFont(new Font("SansSerif", Font.PLAIN, 36));
            String line1 = "No preview available";
            String line2 = filename == null ? "(unknown file)" : filename;
            java.awt.font.FontRenderContext frc = g.getFontRenderContext();
            java.awt.geom.Rectangle2D r1 = g.getFont().getStringBounds(line1, frc);
            java.awt.geom.Rectangle2D r2 = g.getFont().getStringBounds(line2, frc);
            int x1 = (int) ((w - r1.getWidth()) / 2);
            int y1 = h / 2 - 10;
            int x2 = (int) ((w - r2.getWidth()) / 2);
            int y2 = h / 2 + 40;
            g.drawString(line1, x1, y1);
            g.drawString(line2, x2, y2);
        } finally {
            g.dispose();
        }
        return img;
    }
}
