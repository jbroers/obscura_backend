package com.example.obscura_backend.service;

import com.example.obscura_backend.repository.LocalStorageRepository;
import com.example.obscura_backend.repository.StorageRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;

@Service
@Slf4j
public class MinioService {

    private final StorageRepository storageRepository;
    private final LocalStorageRepository localStorageRepository;

    private volatile boolean minioAvailable = true;

    public MinioService(StorageRepository storageRepository,
                       @Autowired(required = false) LocalStorageRepository localStorageRepository) {
        this.storageRepository = storageRepository;
        this.localStorageRepository = localStorageRepository;
    }

    public String uploadFile(MultipartFile file) throws IOException {
        log.debug("Uploading file: {}", file.getOriginalFilename());
        try {
            return storageRepository.uploadFile(file);
        } catch (IOException e) {
            if (isConnectionError(e) && localStorageRepository != null) {
                log.warn("MinIO connection failed, falling back to local storage for file: {}", file.getOriginalFilename());
                markMinioUnavailable();
                return localStorageRepository.uploadFile(file);
            }
            throw e;
        }
    }

    public String uploadBytes(byte[] bytes, String originalFileName, String contentType) throws IOException {
        log.debug("Uploading {} bytes with filename: {}", bytes.length, originalFileName);
        try {
            String result = storageRepository.uploadBytes(bytes, originalFileName, contentType);
            log.debug("Successfully uploaded to MinIO: {}", result);
            return result;
        } catch (IOException e) {
            log.debug("MinIO upload failed, checking if connection error...");
            if (isConnectionError(e) && localStorageRepository != null) {
                log.warn("MinIO connection failed, falling back to local storage for: {}", originalFileName);
                markMinioUnavailable();
                String result = localStorageRepository.uploadBytes(bytes, originalFileName, contentType);
                log.info("Successfully saved to local storage as fallback: {}", result);
                return result;
            }
            log.error("Upload failed and no fallback available", e);
            throw e;
        }
    }

    public String getFileUrl(String fileName) {
        if (!minioAvailable && localStorageRepository != null) {
            return localStorageRepository.getFileUrl(fileName);
        }
        try {
            return storageRepository.getFileUrl(fileName);
        } catch (Exception e) {
            if (isConnectionError(e) && localStorageRepository != null) {
                log.warn("MinIO connection failed, using local storage URL for: {}", fileName);
                markMinioUnavailable();
                return localStorageRepository.getFileUrl(fileName);
            }
            log.error("Error getting file URL", e);
            return null;
        }
    }

    public InputStream downloadFile(String fileName) throws Exception {
        log.debug("Downloading file: {}", fileName);
        try {
            return storageRepository.downloadFile(fileName);
        } catch (Exception e) {
            if (isConnectionError(e) && localStorageRepository != null) {
                log.warn("MinIO connection failed, falling back to local storage for download: {}", fileName);
                markMinioUnavailable();
                return localStorageRepository.downloadFile(fileName);
            }
            throw e;
        }
    }

    public void deleteFile(String fileName) {
        log.debug("Deleting file: {}", fileName);
        try {
            storageRepository.deleteFile(fileName);
        } catch (Exception e) {
            if (isConnectionError(e) && localStorageRepository != null) {
                log.warn("MinIO connection failed, deleting from local storage: {}", fileName);
                markMinioUnavailable();
                localStorageRepository.deleteFile(fileName);
            } else {
                throw e;
            }
        }
    }

    public boolean fileExists(String fileName) {
        if (!minioAvailable && localStorageRepository != null) {
            return localStorageRepository.fileExists(fileName);
        }
        try {
            return storageRepository.fileExists(fileName);
        } catch (Exception e) {
            if (isConnectionError(e) && localStorageRepository != null) {
                markMinioUnavailable();
                return localStorageRepository.fileExists(fileName);
            }
            return false;
        }
    }

    private boolean isConnectionError(Throwable throwable) {
        if (throwable == null) {
            return false;
        }

        if (throwable instanceof ConnectException) {
            return true;
        }

        String className = throwable.getClass().getName();
        if (className.contains("ConnectException")) {
            return true;
        }

        String message = throwable.getMessage();
        if (message != null) {
            String lowerMessage = message.toLowerCase();
            if (lowerMessage.contains("failed to connect") ||
                lowerMessage.contains("connection refused") ||
                lowerMessage.contains("connect") && lowerMessage.contains("fail")) {
                return true;
            }
        }

        return isConnectionError(throwable.getCause());
    }

    private void markMinioUnavailable() {
        if (minioAvailable) {
            minioAvailable = false;
            log.error("MinIO marked as unavailable - all operations will use local storage fallback");
        }
    }
}
