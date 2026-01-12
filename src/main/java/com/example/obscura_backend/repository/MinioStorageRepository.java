package com.example.obscura_backend.repository;

import io.minio.*;
import io.minio.http.Method;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Repository
@Primary
@ConditionalOnProperty(name = "storage.type", havingValue = "minio", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class MinioStorageRepository implements StorageRepository {

    private final MinioClient minioClient;

    @Value("${minio.bucket-name}")
    private String bucketName;

    @Value("${minio.endpoint}")
    private String minioEndpoint;

    @Value("${minio.external-endpoint}")
    private String minioExternalEndpoint;

    @PostConstruct
    @Override
    public void initialize() {
        try {
            boolean bucketExists = minioClient.bucketExists(
                BucketExistsArgs.builder().bucket(bucketName).build()
            );

            if (!bucketExists) {
                minioClient.makeBucket(
                    MakeBucketArgs.builder().bucket(bucketName).build()
                );
                log.info("MinIO bucket '{}' created successfully", bucketName);
            } else {
                log.info("MinIO bucket '{}' already exists", bucketName);
            }
        } catch (Exception e) {
            log.warn("MinIO is not available - will fall back to local storage: {}", e.getMessage());
        }
    }

    @Override
    public String uploadFile(MultipartFile file) throws IOException {
        try {
            String fileName = generateFileName(file.getOriginalFilename());
            InputStream inputStream = file.getInputStream();

            minioClient.putObject(
                PutObjectArgs.builder()
                    .bucket(bucketName)
                    .object(fileName)
                    .stream(inputStream, file.getSize(), -1)
                    .contentType(file.getContentType())
                    .build()
            );

            inputStream.close();
            log.info("File uploaded to MinIO: {}", fileName);
            return fileName;

        } catch (Exception e) {
            log.error("Error uploading file to MinIO", e);
            throw new IOException("Failed to upload file to MinIO", e);
        }
    }

    @Override
    public String uploadBytes(byte[] bytes, String originalFileName, String contentType) throws IOException {
        try {
            String fileName = generateFileName(originalFileName);
            InputStream inputStream = new ByteArrayInputStream(bytes);

            minioClient.putObject(
                PutObjectArgs.builder()
                    .bucket(bucketName)
                    .object(fileName)
                    .stream(inputStream, bytes.length, -1)
                    .contentType(contentType)
                    .build()
            );

            inputStream.close();
            log.info("Bytes uploaded to MinIO: {} ({} bytes)", fileName, bytes.length);
            return fileName;

        } catch (Exception e) {
            log.error("Error uploading bytes to MinIO", e);
            throw new IOException("Failed to upload bytes to MinIO", e);
        }
    }

    @Override
    public String getFileUrl(String fileName) {
        try {
            String url = minioClient.getPresignedObjectUrl(
                GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucketName)
                    .object(fileName)
                    .expiry(7, TimeUnit.DAYS)
                    .build()
            );

            if (!minioEndpoint.equals(minioExternalEndpoint)) {
                url = url.replace(minioEndpoint, minioExternalEndpoint);
                log.debug("Replaced internal endpoint with external: {} -> {}", minioEndpoint, minioExternalEndpoint);
            }

            log.debug("Generated presigned URL for: {}", fileName);
            return url;
        } catch (Exception e) {
            log.error("Error generating presigned URL for file: {}", fileName, e);
            return null;
        }
    }

    @Override
    public InputStream downloadFile(String fileName) throws Exception {
        try {
            InputStream stream = minioClient.getObject(
                GetObjectArgs.builder()
                    .bucket(bucketName)
                    .object(fileName)
                    .build()
            );
            log.debug("Downloaded file from MinIO: {}", fileName);
            return stream;
        } catch (Exception e) {
            log.error("Error downloading file from MinIO: {}", fileName, e);
            throw e;
        }
    }

    @Override
    public void deleteFile(String fileName) {
        try {
            minioClient.removeObject(
                RemoveObjectArgs.builder()
                    .bucket(bucketName)
                    .object(fileName)
                    .build()
            );
            log.info("Deleted file from MinIO: {}", fileName);
        } catch (Exception e) {
            log.error("Error deleting file from MinIO: {}", fileName, e);
            throw new RuntimeException("Failed to delete file from MinIO", e);
        }
    }

    @Override
    public boolean fileExists(String fileName) {
        try {
            minioClient.statObject(
                StatObjectArgs.builder()
                    .bucket(bucketName)
                    .object(fileName)
                    .build()
            );
            return true;
        } catch (Exception e) {
            log.debug("File does not exist in MinIO: {}", fileName);
            return false;
        }
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
}

