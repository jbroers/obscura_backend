package com.example.obscura_backend.service;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.Tag;
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
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

@Service
public class RawImageExtractionService {

    private static final Logger logger = LoggerFactory.getLogger(RawImageExtractionService.class);

    public BufferedImage extractRawPreview(MultipartFile file) {
        BufferedImage image;

        try (InputStream inputStream = file.getInputStream();
             BufferedInputStream bufferedStream = new BufferedInputStream(inputStream)) {

            image = ImageIO.read(bufferedStream);

            if (image != null && image.getWidth() > 0 && image.getHeight() > 0) {
                logger.info("V Loaded RAW with ImageIO: {}x{} pixels",
                        image.getWidth(), image.getHeight());
                return image;
            }
        } catch (Exception e) {
            logger.debug("ImageIO could not read RAW file: {}", e.getMessage());
        }

        try {
            image = extractMetadataPreview(file);
            if (image != null) {
                logger.info("V Extracted metadata preview: {}x{} pixels",
                        image.getWidth(), image.getHeight());
                return image;
            }
        } catch (Exception e) {
            logger.debug("Could not extract metadata preview: {}", e.getMessage());
        }

        try {
            image = findEmbeddedJpegInBytes(file);
            if (image != null) {
                logger.info("V Found embedded JPEG: {}x{} pixels",
                        image.getWidth(), image.getHeight());
                return image;
            }
        } catch (Exception e) {
            logger.debug("Could not find embedded JPEG: {}", e.getMessage());
        }

        logger.warn("Could not extract preview from RAW '{}', using placeholder", file.getOriginalFilename());
        return createPlaceholderImage(file.getOriginalFilename());
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

                        if (tagName.contains("Preview") || tagName.contains("Thumbnail") ||
                                tagName.contains("JPEG") || tagName.contains("Image") ||
                                tagName.contains("Data") || tagName.contains("Offset")) {

                            try {
                                byte[] imageBytes = directory.getByteArray(tag.getTagType());
                                if (imageBytes != null && imageBytes.length > 1000) {
                                    try (ByteArrayInputStream bais = new ByteArrayInputStream(imageBytes)) {
                                        BufferedImage preview = ImageIO.read(bais);
                                        if (preview != null && preview.getWidth() > 100) {
                                            logger.info("Found preview in {} / {}: {}x{}",
                                                    dirName, tagName, preview.getWidth(), preview.getHeight());
                                            return preview;
                                        }
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
                logger.info("Found JPEG #{} at offset {}: {} KB", jpegCount, startIdx, jpegLength / 1024);

                byte[] jpegBytes = new byte[jpegLength];
                System.arraycopy(fileBytes, startIdx, jpegBytes, 0, jpegLength);

                BufferedImage image = tryDecodeWithRepair(jpegBytes, jpegCount);
                if (image != null) {
                    foundImages.add(image);
                }
            }

            searchStartIdx = endIdx;
        }

        logger.info("Scan complete: {} JPEG(s) found, {} decoded", jpegCount, foundImages.size());

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

    private BufferedImage tryDecodeWithRepair(byte[] jpegBytes, int jpegNumber) {

        try (ByteArrayInputStream bais = new ByteArrayInputStream(jpegBytes)) {
            BufferedImage image = ImageIO.read(bais);
            if (image != null && image.getWidth() > 0) {
                logger.info("  V Decoded JPEG #{} with ImageIO: {}x{}", jpegNumber, image.getWidth(), image.getHeight());
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
                    logger.info("  V Decoded JPEG #{} via temp file: {}x{}", jpegNumber, image.getWidth(), image.getHeight());
                    return image;
                }
            } finally {
                Files.deleteIfExists(tempFile);
            }
        } catch (Exception e) {
            logger.debug("  ✗ Temp file failed: {}", e.getMessage());
        }

        logger.warn("  ✗ All decode methods failed for JPEG #{} (likely corrupted or proprietary format)", jpegNumber);
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

            if (exitCode != 0) {
                logger.warn("dcraw exited with code {}", exitCode);
                return null;
            }

            if (Files.exists(tempJpg) && Files.size(tempJpg) > 1000) {
                BufferedImage image = ImageIO.read(tempJpg.toFile());
                if (image != null && image.getWidth() > 0) {
                    logger.info("dcraw successfully extracted preview: {}x{} pixels",
                            image.getWidth(), image.getHeight());
                    return image;
                }
            }

            logger.debug("Trying dcraw with -h flag for half-size conversion");
            pb = new ProcessBuilder(
                "dcraw",
                "-h",
                    "-c",
                    tempDng.toString()
            );

            pb.redirectErrorStream(true);
            process = pb.start();

            try (InputStream processOutput = process.getInputStream()) {
                BufferedImage image = ImageIO.read(processOutput);
                if (image != null && image.getWidth() > 0) {
                    logger.info("dcraw -h successfully converted RAW: {}x{} pixels",
                            image.getWidth(), image.getHeight());
                    return image;
                }
            }

            process.waitFor();

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

    public BufferedImage createPlaceholderImage(String filename) {
        BufferedImage placeholder = new BufferedImage(1200, 900, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = placeholder.createGraphics();

        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        GradientPaint gradient = new GradientPaint(
                0, 0, new Color(60, 60, 70),
                0, 900, new Color(40, 40, 50)
        );
        g2d.setPaint(gradient);
        g2d.fillRect(0, 0, 1200, 900);

        g2d.setColor(new Color(120, 120, 130));
        g2d.fillRoundRect(475, 300, 250, 180, 20, 20);
        g2d.fillRoundRect(520, 260, 160, 60, 10, 10);
        g2d.setColor(new Color(80, 80, 90));
        g2d.fillOval(540, 340, 120, 120);
        g2d.setColor(new Color(150, 150, 160));
        g2d.fillOval(570, 370, 60, 60);

        g2d.setColor(new Color(200, 200, 210));
        g2d.setFont(new Font("SansSerif", Font.BOLD, 36));
        String message = "RAW File";
        FontMetrics fm = g2d.getFontMetrics();
        int x = (1200 - fm.stringWidth(message)) / 2;
        g2d.drawString(message, x, 540);

        g2d.setFont(new Font("SansSerif", Font.PLAIN, 24));
        message = "Preview Not Available";
        fm = g2d.getFontMetrics();
        x = (1200 - fm.stringWidth(message)) / 2;
        g2d.drawString(message, x, 580);

        g2d.setColor(new Color(160, 160, 170));
        g2d.setFont(new Font("SansSerif", Font.PLAIN, 18));
        if (filename != null) {
            if (filename.length() > 60) {
                filename = filename.substring(0, 57) + "...";
            }
            fm = g2d.getFontMetrics();
            x = (1200 - fm.stringWidth(message)) / 2;
            g2d.drawString(filename, x, 640);
        }

        g2d.setColor(new Color(140, 140, 150));
        g2d.setFont(new Font("SansSerif", Font.PLAIN, 14));
        message = "Original RAW file uploaded • EXIF data extracted";
        fm = g2d.getFontMetrics();
        x = (1200 - fm.stringWidth(message)) / 2;
        g2d.drawString(message, x, 700);

        g2d.dispose();

        logger.info("Created placeholder (1200x900) for RAW: {}", filename);
        return placeholder;
    }
}