package com.example.obscura_backend.service;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Metadata;
import com.drew.metadata.Directory;
import com.drew.metadata.Tag;
import com.example.obscura_backend.model.Photo;
import com.example.obscura_backend.repository.PhotoRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.UUID;

import org.apache.tika.Tika;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import net.coobird.thumbnailator.Thumbnails;

@Service
public class PhotoService {

    private static final Logger logger = LoggerFactory.getLogger(PhotoService.class);
    private final Tika tika = new Tika();
    private final PhotoRepository photoRepository;

    @Value("${photo.upload-dir:/uploads}")
    private String uploadDirPath;

    private Path uploadDir;

    public PhotoService(PhotoRepository photoRepository) {
        this.photoRepository = photoRepository;
    }

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

    public Photo savePhoto(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("No file uploaded");
        }

        if (!isImage(file)) {
            throw new IllegalArgumentException("Uploaded file is not a valid image format!");
        }

        // Extract metadata
        Metadata metadata = extractMetadata(file);

        // Upload file and get path
        String filePath = uploadFile(file);

        // Create Photo entity with metadata
        Photo photo = createPhotoEntity(file, filePath, metadata);

        // Save to database
        return photoRepository.save(photo);
    }

    private boolean isImage(MultipartFile file) throws IOException {
        String mimeType = tika.detect(file.getInputStream());
        return mimeType != null && mimeType.startsWith("image/");
    }

    private Metadata extractMetadata(MultipartFile file) throws IOException {
        Metadata metadata = null;

        try (InputStream inputStream = file.getInputStream()) {
            metadata = ImageMetadataReader.readMetadata(inputStream);
        } catch (ImageProcessingException e) {
            logger.warn("Failed to read image metadata: {}", e.getMessage());
        }
        return metadata;
    }

    private Photo createPhotoEntity(MultipartFile file, String filePath, Metadata metadata) {
        Photo photo = new Photo();
        photo.setFileName(file.getOriginalFilename());
        photo.setFilePath(filePath);
        photo.setContentType(file.getContentType());
        photo.setFileSize(file.getSize());
        photo.setUploadedAt(LocalDateTime.now());

        // Extract specific metadata if available
        if (metadata != null) {
            StringBuilder metadataStr = new StringBuilder();
            for (Directory directory : metadata.getDirectories()) {
                for (Tag tag : directory.getTags()) {
                    metadataStr.append(tag.getTagName())
                            .append(": ")
                            .append(tag.getDescription())
                            .append("\n");
                }
            }
            photo.setMetadata(metadataStr.toString());
        }

        return photo;
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

    private String uploadFile(MultipartFile file) throws IOException {
        byte[] compressed = compressImage(file.getBytes());

        String original = file.getOriginalFilename();
        String uniqueFileName = UUID.randomUUID() + "_" +
                sanitizeFileName(original != null ? original : "upload-" + System.currentTimeMillis());
        Path target = uploadDir.resolve(uniqueFileName);

        Files.write(target, compressed, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        logger.info("Saved uploaded file to {}", target.toAbsolutePath());

        return target.toString();
    }
}
