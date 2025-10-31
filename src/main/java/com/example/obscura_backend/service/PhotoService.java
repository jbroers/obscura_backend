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
import java.util.Arrays;
import java.util.List;

import org.apache.tika.Tika;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import net.coobird.thumbnailator.Thumbnails;

@Service
public class PhotoService {

    private static final Logger logger = LoggerFactory.getLogger(PhotoService.class);
    private final Tika tika = new Tika();
    private final PhotoRepository photoRepository;

    private static final List<String> RAW_EXTENSIONS = Arrays.asList(
            "cr2", "cr3", "nef", "arw", "dng", "orf", "raf", "rw2", "pef", "srw", "craw"
    );

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

    public Photo savePhoto(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("No file uploaded");
        }

        if (!isImage(file)) {
            throw new IllegalArgumentException("Uploaded file is not a valid image format!");
        }

        String originalFileName = file.getOriginalFilename();
        boolean isRaw = isRawFormat(originalFileName);
        long originalFileSize = file.getSize();

        Metadata metadata = extractMetadata(file);

        BufferedImage image = loadImage(file, isRaw);

        String filePath = compressAndSave(image, originalFileName);

        Photo photo = createPhotoEntity(file, filePath, metadata, isRaw, originalFileSize);

        return photoRepository.save(photo);
    }

    private boolean isImage(MultipartFile file) throws IOException {
        if (isRawFormat(file.getOriginalFilename())) {
            return true;
        }

        String mimeType = tika.detect(file.getInputStream());
        return mimeType != null && (mimeType.startsWith("image/") || isRawFormat(file.getOriginalFilename()));
    }

    private boolean isRawFormat(String fileName) {
        if (fileName == null) return false;
        String extension = getFileExtension(fileName).toLowerCase();
        return RAW_EXTENSIONS.contains(extension);
    }

    private String getFileExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        return lastDot > 0 ? fileName.substring(lastDot + 1) : "";
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

    private BufferedImage loadImage(MultipartFile file, boolean isRaw) throws IOException {
        try {
            if (isRaw) {
                // For RAW files, try to extract embedded preview
                return extractRawPreview(file);
            }

            // For regular images
            try (InputStream inputStream = file.getInputStream()) {
                BufferedImage image = ImageIO.read(inputStream);
                if (image == null) {
                    throw new IllegalArgumentException("Unable to process image file");
                }
                return image;
            }
        } catch (Exception e) {
            logger.error("Failed to load image: {}", e.getMessage());
            throw new IOException("Could not process image: " + e.getMessage(), e);
        }
    }

    private BufferedImage extractRawPreview(MultipartFile file) throws IOException {
        try (InputStream inputStream = file.getInputStream()) {
            Metadata metadata = ImageMetadataReader.readMetadata(inputStream);

            // Try to find embedded JPEG in RAW file
            for (Directory directory : metadata.getDirectories()) {
                if (directory.getName().contains("JPEG") || directory.getName().contains("Preview")) {
                    // Found preview directory - try to extract it
                    logger.info("Found preview in RAW file");
                }
            }

            // Fallback: try ImageIO (works with TwelveMonkeys plugins)
            inputStream.reset();
            BufferedImage image = ImageIO.read(inputStream);

            if (image != null) {
                return image;
            }

            // Last resort: create placeholder
            logger.warn("Could not extract preview from RAW file, creating placeholder");
            return createPlaceholderImage();

        } catch (Exception e) {
            logger.error("Failed to extract RAW preview: {}", e.getMessage());
            return createPlaceholderImage();
        }
    }

    private BufferedImage createPlaceholderImage() {
        BufferedImage placeholder = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB);
        // Optionally: add text "RAW Preview Unavailable"
        return placeholder;
    }

    private String compressAndSave(BufferedImage image, String originalFileName) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        Thumbnails.of(image)
                .size(1920, 1920)
                .outputFormat("jpg")
                .outputQuality(0.8)
                .toOutputStream(baos);

        String baseFileName = sanitizeFileName(originalFileName);
        String extension = getFileExtension(baseFileName);
        String nameWithoutExt = baseFileName.substring(0, baseFileName.length() - extension.length() - 1);
        String uniqueFileName = UUID.randomUUID() + "_" + nameWithoutExt + ".jpg";

        Path targetPath = uploadDir.resolve(uniqueFileName);

        byte[] compressedBytes = baos.toByteArray();
        Files.write(targetPath, compressedBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        logger.info("Saved compressed image to {}", targetPath.toAbsolutePath());

        return targetPath.toString();
    }

    private Photo createPhotoEntity(MultipartFile file, String filePath, Metadata metadata,
                                    boolean isRaw, long originalFileSize) throws IOException {
        Photo photo = new Photo();
        photo.setFileName(file.getOriginalFilename());
        photo.setFilePath(filePath);
        photo.setContentType("image/jpeg");
        Path path = Paths.get(filePath);
        photo.setFileSize(Files.size(path));

        photo.setUploadedAt(LocalDateTime.now());
        photo.setIsRaw(isRaw);

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

    private String sanitizeFileName(String name) {
        return name.replaceAll("[\\\\/]+", "_").replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
