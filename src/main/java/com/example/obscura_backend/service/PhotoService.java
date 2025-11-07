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
                return rawImageExtractionService.extractRawPreview(file);
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

    private Photo createPhotoEntity(MultipartFile file, String filePath, Metadata metadata,
                                    boolean isRaw, long originalFileSize) throws IOException {
        Photo photo = new Photo();
        photo.setFileName(file.getOriginalFilename());
        photo.setFilePath(filePath);
        photo.setContentType(file.getContentType());
        Path path = Paths.get(filePath);
        photo.setFileSize(Files.size(path));
        photo.setUploadedAt(LocalDateTime.now());
        photo.setIsRaw(isRaw);

        if (metadata != null) {
            exifExtractionService.extractExifData(metadata, photo);

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
        }

        return photo;
    }

    public List<Photo> getAllPhotos() {
        return photoRepository.findAll();
    }

    private String sanitizeFileName(String name) {
        return name.replaceAll("[\\\\/]+", "_").replaceAll("[^A-Za-z0-9._-]", "_");
    }
}