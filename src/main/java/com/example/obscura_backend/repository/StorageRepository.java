package com.example.obscura_backend.repository;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

public interface StorageRepository {

    void initialize();

    String uploadFile(MultipartFile file) throws IOException;

    String uploadBytes(byte[] bytes, String originalFileName, String contentType) throws IOException;

    String getFileUrl(String fileName);

    InputStream downloadFile(String fileName) throws Exception;

    void deleteFile(String fileName);

    boolean fileExists(String fileName);
}

