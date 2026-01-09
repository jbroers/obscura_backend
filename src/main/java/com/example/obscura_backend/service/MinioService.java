package com.example.obscura_backend.service;

import com.example.obscura_backend.repository.StorageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

@Service
@RequiredArgsConstructor
@Slf4j
public class MinioService {

    private final StorageRepository storageRepository;

    public String uploadFile(MultipartFile file) throws IOException {
        log.debug("Uploading file: {}", file.getOriginalFilename());
        return storageRepository.uploadFile(file);
    }

    public String uploadBytes(byte[] bytes, String originalFileName, String contentType) throws IOException {
        log.debug("Uploading {} bytes with filename: {}", bytes.length, originalFileName);
        return storageRepository.uploadBytes(bytes, originalFileName, contentType);
    }

    public String getFileUrl(String fileName) {
        return storageRepository.getFileUrl(fileName);
    }

    public InputStream downloadFile(String fileName) throws Exception {
        log.debug("Downloading file: {}", fileName);
        return storageRepository.downloadFile(fileName);
    }

    public void deleteFile(String fileName) {
        log.debug("Deleting file: {}", fileName);
        storageRepository.deleteFile(fileName);
    }

    public boolean fileExists(String fileName) {
        return storageRepository.fileExists(fileName);
    }
}
