package com.example.obscura_backend.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;
import java.io.*;
import java.nio.file.*;
import java.util.UUID;

@Repository
@ConditionalOnProperty(name = "storage.type", havingValue = "local", matchIfMissing = false)
@RequiredArgsConstructor
@Slf4j
public class LocalStorageRepository implements StorageRepository {

    @Value("${storage.local.upload-dir:/uploads}")
    private String uploadDirPath;

    private Path uploadDir;

    @Value("${server.base-url:http://localhost:8080}")
    private String baseUrl;

    @PostConstruct
    @Override
    public void initialize() {
        if (uploadDirPath == null || uploadDirPath.isBlank()) {
            throw new IllegalStateException("Upload directory path is not set");
        }

        uploadDir = Paths.get(uploadDirPath);

        try {
            Files.createDirectories(uploadDir);
            log.info("Local storage initialized at: {}", uploadDir.toAbsolutePath());
        } catch (IOException e) {
            log.error("Could not create upload directory: {}", uploadDir, e);
            throw new RuntimeException("Failed to initialize local storage", e);
        }
    }

    @Override
    public String uploadFile(MultipartFile file) throws IOException {
        try {
            if (!Files.exists(uploadDir)) {
                Files.createDirectories(uploadDir);
            }

            String fileName = generateFileName(file.getOriginalFilename());
            Path targetPath = uploadDir.resolve(fileName);

            try (InputStream inputStream = file.getInputStream()) {
                Files.copy(inputStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }

            log.info("File uploaded to local storage: {}", fileName);
            return fileName;

        } catch (Exception e) {
            log.error("Error uploading file to local storage", e);
            throw new IOException("Failed to upload file to local storage", e);
        }
    }

    @Override
    public String uploadBytes(byte[] bytes, String originalFileName, String contentType) throws IOException {
        try {
            if (uploadDir == null) {
                uploadDir = Paths.get(uploadDirPath);
            }
            if (!Files.exists(uploadDir)) {
                Files.createDirectories(uploadDir);
                log.debug("Created upload directory: {}", uploadDir.toAbsolutePath());
            }

            String fileName = generateFileName(originalFileName);
            Path targetPath = uploadDir.resolve(fileName);

            Files.write(targetPath, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            log.info("Bytes uploaded to local storage: {} ({} bytes)", fileName, bytes.length);
            return fileName;

        } catch (Exception e) {
            log.error("Error uploading bytes to local storage", e);
            throw new IOException("Failed to upload bytes to local storage", e);
        }
    }

    @Override
    public String getFileUrl(String fileName) {
        String url = baseUrl + "/api/photos/files/" + fileName;
        log.debug("Generated local file URL: {}", url);
        return url;
    }

    @Override
    public InputStream downloadFile(String fileName) throws Exception {
        try {
            Path filePath = uploadDir.resolve(fileName);
            if (!Files.exists(filePath)) {
                throw new FileNotFoundException("File not found: " + fileName);
            }
            return Files.newInputStream(filePath);
        } catch (Exception e) {
            log.error("Error downloading file from local storage: {}", fileName, e);
            throw e;
        }
    }

    @Override
    public void deleteFile(String fileName) {
        try {
            Path filePath = uploadDir.resolve(fileName);
            Files.deleteIfExists(filePath);
            log.info("Deleted file from local storage: {}", fileName);
        } catch (Exception e) {
            log.error("Error deleting file from local storage: {}", fileName, e);
            throw new RuntimeException("Failed to delete file from local storage", e);
        }
    }

    @Override
    public boolean fileExists(String fileName) {
        Path filePath = uploadDir.resolve(fileName);
        return Files.exists(filePath);
    }

    private String generateFileName(String originalFileName) {
        String extension = getFileExtension(originalFileName);
        return UUID.randomUUID().toString() + extension;
    }

    private String getFileExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "";
        }
        return fileName.substring(fileName.lastIndexOf("."));
    }

    public Path getUploadDir() {
        return uploadDir;
    }
}

