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

    private static final List<String> RAW_EXTENSIONS = Arrays.asList(
            "cr2", "cr3", "nef", "arw", "dng", "orf", "raf", "rw2", "pef", "srw", "craw"
    );

    @Value("${photo.upload-dir:/uploads}")
    private String uploadDirPath;

    private Path uploadDir;

    public PhotoService(PhotoRepository photoRepository,
                        RawImageExtractionService rawImageExtractionService,
                        ExifExtractionService exifExtractionService) {
        this.photoRepository = photoRepository;
        this.rawImageExtractionService = rawImageExtractionService;
        this.exifExtractionService = exifExtractionService;
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

        String[] readerFormats = ImageIO.getReaderFormatNames();
        logger.info("Available ImageIO readers: {}", String.join(", ", readerFormats));

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
                        int dirCount = (int) java.util.stream.StreamSupport.stream(embedded.getDirectories().spliterator(), false).count();
                        logger.info("Embedded JPEG metadata found ({} directories)", dirCount);
                        try { exifExtractionService.logMetadataSummary(embedded, "embedded"); } catch (Exception ignored) {}

                        if (metadata == null) {
                            metadata = embedded;
                            logger.info("Using embedded JPEG metadata because top-level metadata was null");
                        } else {
                            boolean topHasDate = hasDateInMetadata(metadata);
                            boolean embeddedHasDate = hasDateInMetadata(embedded);
                            int topTags = countMetadataTags(metadata);
                            int embeddedTags = countMetadataTags(embedded);
                            if (embeddedHasDate && !topHasDate) {
                                metadata = embedded;
                                logger.info("Prefer embedded metadata because it contains a date");
                            } else if (embeddedTags > topTags + 5) {
                                metadata = embedded;
                                logger.info("Prefer embedded metadata because it has more tags ({} > {})", embeddedTags, topTags);
                            } else {
                                logger.debug("Keeping top-level metadata (embeddedTags={}, topTags={}, embeddedHasDate={}, topHasDate={})",
                                        embeddedTags, topTags, embeddedHasDate, topHasDate);
                            }
                        }
                    }
                }
                try { if (metadata != null) exifExtractionService.logMetadataSummary(metadata, "selected"); } catch (Exception ignored) {}
            } catch (Exception e) {
                logger.debug("Embedded preview+metadata extraction failed: {}", e.getMessage());
            }
        }

        if (metadata == null) {
            logger.debug("Metadata remains null after attempts");
        }

        try {
            if (metadata == null || photoMetadataIncomplete(metadata)) {
                Metadata embeddedOnly = rawImageExtractionService.extractMetadataFromEmbeddedJpeg(file);
                if (embeddedOnly != null) {
                    logger.info("Fallback: extracted metadata from embedded JPEG/dcraw preview");
                    try { exifExtractionService.logMetadataSummary(embeddedOnly, "fallback-embedded"); } catch (Exception ignored) {}
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
                    logger.info("Using exiftool fallback with {} tags", exifmap.size());
                }
            }
        } catch (Exception e) {
            logger.debug("exiftool fallback failed: {}", e.getMessage());
        }

        try {
            java.util.Map<String,String> topMap = exifExtractionService.collectMetadataFromFile(file);
            java.util.Map<String,String> embeddedMap = (embeddedMetadata != null) ? exifExtractionService.collectMetadata(embeddedMetadata) : new java.util.LinkedHashMap<>();
            java.util.Map<String,String> selectedMap = (metadata != null) ? exifExtractionService.collectMetadata(metadata) : new java.util.LinkedHashMap<>();
            java.util.Map<String,String> exiftoolMapCopy = (exiftoolMap != null) ? new java.util.LinkedHashMap<>(exiftoolMap) : new java.util.LinkedHashMap<>();

            java.util.Map<String,String> topFields = exifExtractionService.extractCommonFieldsFromMap(topMap);
            java.util.Map<String,String> embeddedFields = exifExtractionService.extractCommonFieldsFromMap(embeddedMap);
            java.util.Map<String,String> selectedFields = exifExtractionService.extractCommonFieldsFromMap(selectedMap);
            java.util.Map<String,String> exiftoolFields = exifExtractionService.extractCommonFieldsFromMap(exiftoolMapCopy);

            String[] keys = new String[]{"Make","Model","Lens","ISO","Aperture","ShutterSpeed","FocalLength","Resolution","DateTaken","GPSLatitude","GPSLongitude","Orientation"};
            StringBuilder coverage = new StringBuilder();
            coverage.append("Metadata coverage report:\n");
            for (String k : keys) {
                coverage.append(String.format("  %-12s : top=%s, embedded=%s, selected=%s, exiftool=%s\n",
                        k,
                        (notEmpty(topFields.get(k)) ? "Y" : "-"),
                        (notEmpty(embeddedFields.get(k)) ? "Y" : "-"),
                        (notEmpty(selectedFields.get(k)) ? "Y" : "-"),
                        (notEmpty(exiftoolFields.get(k)) ? "Y" : "-")
                ));
            }
            logger.info(coverage.toString());
        } catch (Exception e) {
            logger.debug("Metadata coverage diagnostics failed: {}", e.getMessage());
        }

        BufferedImage image = previewFromRaw != null ? previewFromRaw : loadImage(file, isRaw);

        String filePath = compressAndSave(image, originalFileName);

        java.util.Map<String, String> cr3map = new java.util.LinkedHashMap<>();
        try {
            if (isRaw) {
                cr3map = CR3Parser.parse(file);
                if (cr3map != null && !cr3map.isEmpty()) logger.info("CR3 parser returned {} keys: {}", cr3map.size(), cr3map.keySet());
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
        logger.info("Original image size: {}x{}", originalWidth, originalHeight);

        int maxSize = 2560;
        double quality = 0.92;

        if (originalWidth < 500 && originalHeight < 500) {
            logger.warn("Image is very small ({}x{}), this might be a thumbnail. Saving as-is with high quality.",
                    originalWidth, originalHeight);
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
        String uniqueFileName = UUID.randomUUID() + "_" + nameWithoutExt + ".jpg";

        Path targetPath = uploadDir.resolve(uniqueFileName);

        byte[] compressedBytes = baos.toByteArray();
        Files.write(targetPath, compressedBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        long savedSize = compressedBytes.length;
        logger.info("Saved image to {} ({}x{} -> {} KB)",
                targetPath.getFileName(), originalWidth, originalHeight, savedSize / 1024);

        return targetPath.toString();
    }

    private Photo createPhotoEntity(MultipartFile file, String filePath, Metadata primaryMetadata, Metadata secondaryMetadata,
                                    boolean isRaw, java.util.Map<String, String> exiftoolMap, java.util.Map<String, String> cr3map) throws IOException {
        Photo photo = new Photo();
        photo.setFileName(file.getOriginalFilename());
        photo.setFilePath(filePath);
        photo.setContentType(file.getContentType());
        Path path = Paths.get(filePath);
        photo.setFileSize(Files.size(path));
        photo.setUploadedAt(LocalDateTime.now());
        photo.setIsRaw(isRaw);

        if (primaryMetadata != null) {
            exifExtractionService.extractExifData(primaryMetadata, photo);
        }
        if (secondaryMetadata != null) {
            exifExtractionService.extractExifData(secondaryMetadata, photo);
        }

        if ((primaryMetadata != null || secondaryMetadata != null)) {
            logger.info("Extracted EXIF data for {}: Make={}, Model={}, Lens={}, ISO={}, Aperture={}, Shutter={}, Focal={}, Resolution={}, Date={}, GPS={},{}",
                    file.getOriginalFilename(),
                    photo.getCameraMake(),
                    photo.getCameraModel(),
                    photo.getLensModel(),
                    photo.getIso(),
                    photo.getAperture(),
                    photo.getShutterSpeed(),
                    photo.getFocalLength(),
                    photo.getResolution(),
                    photo.getDateTaken(),
                    photo.getGpsLatitude(),
                    photo.getGpsLongitude());
        } else if (exiftoolMap != null) {
            exifExtractionService.populateFromMap(exiftoolMap, photo);
            logger.info("Populated EXIF data from exiftool map for {}", file.getOriginalFilename());
        }

        if (cr3map != null && !cr3map.isEmpty()) {
            exifExtractionService.populateFromMap(cr3map, photo);
            logger.info("Populated EXIF data from CR3 parser map for {}", file.getOriginalFilename());
            try {
                logger.info("CR3 map keys: {}", cr3map.keySet());
                logger.info("Photo after CR3 map: Make={}, Model={}, Lens={}, ISO={}, Aperture={}, Shutter={}, Focal={}, Resolution={}, Date={}, GPS={},{}",
                        photo.getCameraMake(), photo.getCameraModel(), photo.getLensModel(), photo.getIso(), photo.getAperture(), photo.getShutterSpeed(), photo.getFocalLength(), photo.getResolution(), photo.getDateTaken(), photo.getGpsLatitude(), photo.getGpsLongitude());
            } catch (Exception ignored) {}
        }

        return photo;
    }

    public List<Photo> getAllPhotos() {
        return photoRepository.findAll();
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
