package com.example.obscura_backend.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.*;

import org.apache.tika.Tika;
import javax.imageio.ImageIO;
import net.coobird.thumbnailator.Thumbnails;

@Service
public class PhotoService {

    private static final Logger logger = LoggerFactory.getLogger(PhotoService.class);
    private final Tika tika = new Tika();

    @Value("${photo.upload-dir:/uploads}")
    private String uploadDirPath;

    private Path uploadDir;

    @PostConstruct
    public void init() {
        if (uploadDirPath == null || uploadDirPath.isBlank()) {
            throw new IllegalStateException("Upload directory path is not set");
        }

        uploadDir = Paths.get(uploadDirPath);

        try {
            Files.createDirectories(uploadDir);
        } catch (IOException e) {
            logger.error("Could not create upload dir: {}", uploadDir, e);
            throw new RuntimeException(e);
        }

        logger.info("Upload directory resolved to: {}", uploadDir.toAbsolutePath());
    }

    public void setUploadDirPath(String path) {
        this.uploadDirPath = path;
    }

    public void savePhoto(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("No file uploaded");
        }

        if (!isImage(file)) {
            throw new IllegalArgumentException("Uploaded file is not a valid image format!");
        }

        byte[] compressed = compressImage(file.getBytes());

        String original = file.getOriginalFilename();
        String safeName = sanitizeFileName(original != null ? original : "upload-" + System.currentTimeMillis());
        Path target = uploadDir.resolve(safeName);

        Files.write(target, compressed, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        logger.info("Saved uploaded file to {}", target.toAbsolutePath());
    }

    private boolean isImage(MultipartFile file) throws IOException {
        String mimeType = tika.detect(file.getInputStream());
        return mimeType != null && mimeType.startsWith("image/");
    }

    private byte[] compressImage(byte[] bytes) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        if (image == null) {
            throw new IllegalArgumentException("Unable to read image");
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Thumbnails.of(image)
                .scale(1.0)
                .outputFormat("jpg")
                .outputQuality(0.5)
                .toOutputStream(baos);

        return baos.toByteArray();
    }

    private String sanitizeFileName(String name) {
        return name.replaceAll("[\\\\/]+", "_").replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
