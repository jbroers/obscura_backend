package com.example.obscura_backend.service;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Metadata;
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
import com.example.obscura_backend.service.raw.RawImageExtractionService;
import com.example.obscura_backend.service.exif.ExifExtractionService;
import com.example.obscura_backend.service.exif.CR3Parser;

@Service
public class PhotoService {

    private static final Logger logger = LoggerFactory.getLogger(PhotoService.class);
    private final Tika tika = new Tika();
    private final PhotoRepository photoRepository;
    private final RawImageExtractionService rawImageExtractionService;
    private final ExifExtractionService exifExtractionService;
    private final MinioService minioService;

    private static final List<String> RAW_EXTENSIONS = Arrays.asList(
            "cr2", "cr3", "nef", "arw", "dng", "orf", "raf", "rw2", "pef", "srw", "craw"
    );

    public PhotoService(PhotoRepository photoRepository,
                        RawImageExtractionService rawImageExtractionService,
                        ExifExtractionService exifExtractionService,
                        MinioService minioService) {
        this.photoRepository = photoRepository;
        this.rawImageExtractionService = rawImageExtractionService;
        this.exifExtractionService = exifExtractionService;
        this.minioService = minioService;
    }

    public List<Photo> savePhotos(List<MultipartFile> files) throws IOException {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("No files uploaded");
        }

        logger.info("Starting batch upload of {} file(s)", files.size());
        List<Photo> savedPhotos = new java.util.ArrayList<>();
        int successCount = 0;

        for (int i = 0; i < files.size(); i++) {
            MultipartFile file = files.get(i);
            try {
                logger.debug("Processing file {}/{}: {}", i + 1, files.size(), file.getOriginalFilename());
                Photo photo = savePhoto(file);
                savedPhotos.add(photo);
                successCount++;
            } catch (Exception e) {
                logger.error("Failed to save photo {}/{} ({}): {}", i + 1, files.size(), file.getOriginalFilename(), e.getMessage());
                throw new IOException("Failed to save photo: " + file.getOriginalFilename(), e);
            }
        }

        logger.info("Batch upload completed: {}/{} files saved successfully", successCount, files.size());
        return savedPhotos;
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

        Metadata topMetadata = null;
        try {
            topMetadata = extractMetadata(file);
        } catch (Exception e) {
            logger.debug("Top-level metadata read failed: {}", e.getMessage());
        }

        Metadata metadata = topMetadata;
        Metadata embeddedMetadata = null;
        BufferedImage previewFromRaw = null;

        if (isRaw) {
            try {
                RawImageExtractionService.PreviewResult pr = rawImageExtractionService.extractRawPreviewWithMetadata(file);
                if (pr != null) {
                    previewFromRaw = pr.preview;
                    Metadata embedded = pr.metadata;
                    if (embedded != null) {
                        embeddedMetadata = embedded;
                        if (metadata == null) {
                            metadata = embedded;
                        } else {
                            boolean topHasDate = hasDateInMetadata(metadata);
                            boolean embeddedHasDate = hasDateInMetadata(embedded);
                            int topTags = countMetadataTags(metadata);
                            int embeddedTags = countMetadataTags(embedded);
                            if (embeddedHasDate && !topHasDate) {
                                metadata = embedded;
                            } else if (embeddedTags > topTags + 5) {
                                metadata = embedded;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                logger.debug("Embedded preview+metadata extraction failed: {}", e.getMessage());
            }
        }

        try {
            if (metadata == null || photoMetadataIncomplete(metadata)) {
                Metadata embeddedOnly = rawImageExtractionService.extractMetadataFromEmbeddedJpeg(file);
                if (embeddedOnly != null) {
                    if (metadata == null) {
                        metadata = embeddedOnly;
                    } else {
                        int existingTags = countMetadataTags(metadata);
                        int fallbackTags = countMetadataTags(embeddedOnly);
                        if (fallbackTags > existingTags) metadata = embeddedOnly;
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Fallback embedded metadata extraction failed: {}", e.getMessage());
        }

        java.util.Map<String, String> exiftoolMap = null;
        try {
            if (metadata == null || (photoMetadataIncomplete(metadata) && rawImageExtractionService != null)) {
                java.util.Map<String, String> exifmap = rawImageExtractionService.extractMetadataWithExiftool(file);
                if (exifmap != null && !exifmap.isEmpty()) {
                    exiftoolMap = exifmap;
                }
            }
        } catch (Exception e) {
            logger.debug("exiftool fallback failed: {}", e.getMessage());
        }


        BufferedImage image = previewFromRaw != null ? previewFromRaw : loadImage(file, isRaw);

        String filePath = compressAndSave(image, originalFileName);

        java.util.Map<String, String> cr3map = new java.util.LinkedHashMap<>();
        try {
            if (isRaw) {
                cr3map = CR3Parser.parse(file);
            }
        } catch (Exception e) {
            logger.debug("CR3 parser call failed: {}", e.getMessage());
        }

        Photo photo = createPhotoEntity(file, filePath, topMetadata, embeddedMetadata != null ? embeddedMetadata : metadata, isRaw, exiftoolMap, cr3map);

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
                RawImageExtractionService.PreviewResult pr = rawImageExtractionService.extractRawPreviewWithMetadata(file);
                if (pr != null && pr.preview != null) {
                    return pr.preview;
                }
                return rawImageExtractionService.createPlaceholderImage(file.getOriginalFilename());
            }

            try (InputStream inputStream = file.getInputStream();
                 BufferedInputStream bufferedStream = new BufferedInputStream(inputStream)) {
                BufferedImage image = ImageIO.read(bufferedStream);
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


    private String compressAndSave(BufferedImage image, String originalFileName) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        int originalWidth = image.getWidth();
        int originalHeight = image.getHeight();

        int maxSize = 2560;
        double quality = 0.92;

        if (originalWidth < 500 && originalHeight < 500) {
            maxSize = Math.max(originalWidth, originalHeight);
            quality = 0.95;
        }

        Thumbnails.of(image)
                .size(maxSize, maxSize)
                .outputFormat("jpg")
                .outputQuality(quality)
                .toOutputStream(baos);

        String baseFileName = sanitizeFileName(originalFileName);
        String extension = getFileExtension(baseFileName);
        String nameWithoutExt = baseFileName.substring(0, baseFileName.length() - extension.length() - 1);
        String uniqueFileName = UUID.randomUUID() + ".jpg";

        byte[] compressedBytes = baos.toByteArray();

        String minioFileName = minioService.uploadBytes(compressedBytes, uniqueFileName, "image/jpeg");


        return minioFileName;
    }

    private Photo createPhotoEntity(MultipartFile file, String filePath, Metadata primaryMetadata, Metadata secondaryMetadata,
                                    boolean isRaw, java.util.Map<String, String> exiftoolMap, java.util.Map<String, String> cr3map) throws IOException {
        Photo photo = new Photo();
        photo.setFileName(file.getOriginalFilename());
        photo.setFilePath(filePath);
        photo.setContentType(file.getContentType());
        photo.setFileSize(file.getSize());

        String fileUrl = minioService.getFileUrl(filePath);
        photo.setFileUrl(fileUrl);

        photo.setUploadedAt(LocalDateTime.now());
        photo.setIsRaw(isRaw);

        if (primaryMetadata != null) {
            exifExtractionService.extractExifData(primaryMetadata, photo);
        }
        if (secondaryMetadata != null) {
            exifExtractionService.extractExifData(secondaryMetadata, photo);
        }

        if (exiftoolMap != null && !exiftoolMap.isEmpty()) {
            exifExtractionService.populateFromMap(exiftoolMap, photo);
        }

        if (cr3map != null && !cr3map.isEmpty()) {
            exifExtractionService.populateFromMap(cr3map, photo);
        }

        logger.debug("Extracted metadata for {}: {}, {}, ISO {}",
            file.getOriginalFilename(),
            photo.getCameraMake(),
            photo.getCameraModel(),
            photo.getIso());

        return photo;
    }

    public List<Photo> getAllPhotos() {
        return photoRepository.findAll();
    }

    public void deletePhoto(Long photoId) {
        Photo photo = photoRepository.findById(photoId)
                .orElseThrow(() -> new RuntimeException("Photo not found with id: " + photoId));

        if (photo.getFilePath() != null) {
            try {
                minioService.deleteFile(photo.getFilePath());
                logger.info("Deleted photo from MinIO: {}", photo.getFilePath());
            } catch (Exception e) {
                logger.error("Failed to delete photo from MinIO: {}", photo.getFilePath(), e);
            }
        }

        photoRepository.delete(photo);
        logger.info("Deleted photo from database: {}", photoId);
    }

    private String sanitizeFileName(String name) {
        return name.replaceAll("[\\\\/]+", "_").replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private boolean hasDateInMetadata(com.drew.metadata.Metadata meta) {
        if (meta == null) return false;
        for (com.drew.metadata.Directory dir : meta.getDirectories()) {
            for (com.drew.metadata.Tag tag : dir.getTags()) {
                String name = tag.getTagName();
                String desc = tag.getDescription();
                if ((name != null && name.toLowerCase().contains("date")) ||
                        (name != null && name.toLowerCase().contains("time")) ||
                        (desc != null && (desc.contains("-") || desc.contains("T") || desc.matches(".*\\d{4}.*")))) {
                    return true;
                }
            }
        }
        return false;
    }

    private int countMetadataTags(com.drew.metadata.Metadata meta) {
        if (meta == null) return 0;
        int count = 0;
        for (com.drew.metadata.Directory dir : meta.getDirectories()) {
            count += dir.getTags().size();
        }
        return count;
    }

    private boolean photoMetadataIncomplete(Metadata metadata) {
        if (metadata == null) return true;
        boolean hasDate = false;
        boolean hasCameraInfo = false;
        boolean hasLocation = false;

        for (com.drew.metadata.Directory dir : metadata.getDirectories()) {
            for (com.drew.metadata.Tag tag : dir.getTags()) {
                String name = tag.getTagName();
                if (name != null && name.equalsIgnoreCase("DateTimeOriginal")) {
                    hasDate = true;
                }
                if (name != null && (name.equalsIgnoreCase("Make") || name.equalsIgnoreCase("Model"))) {
                    hasCameraInfo = true;
                }
                if (name != null && (name.equalsIgnoreCase("GPSLatitude") || name.equalsIgnoreCase("GPSLongitude"))) {
                    hasLocation = true;
                }
            }
        }

        return !hasDate || !hasCameraInfo || !hasLocation;
    }

    private boolean notEmpty(String s) {
        return s != null && !s.isBlank();
    }
}