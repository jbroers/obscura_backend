package com.example.obscura_backend.repository;

import io.minio.*;
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

    @PostConstruct
    @Override
    public void initialize() {
        try {
            log.info("MinIO client endpoint: {}", minioEndpoint);
            log.info("Using public bucket with direct URLs (no presigned URLs)");

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

            String policy = String.format("""
                {
                    "Version": "2012-10-17",
                    "Statement": [
                        {
                            "Effect": "Allow",
                            "Principal": {"AWS": "*"},
                            "Action": ["s3:GetObject"],
                            "Resource": ["arn:aws:s3:::%s/*"]
                        }
                    ]
                }
                """, bucketName);

            minioClient.setBucketPolicy(
                SetBucketPolicyArgs.builder()
                    .bucket(bucketName)
                    .config(policy)
                    .build()
            );
            log.info("MinIO bucket '{}' set to public (anonymous download)", bucketName);

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
            String publicUrl = String.format("http://localhost:9000/%s/%s", bucketName, fileName);

            log.info("Generated public URL for: {}", fileName);
            log.debug("Public URL: {}", publicUrl);
            return publicUrl;
        } catch (Exception e) {
            log.error("Error generating URL for file: {}", fileName, e);
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

