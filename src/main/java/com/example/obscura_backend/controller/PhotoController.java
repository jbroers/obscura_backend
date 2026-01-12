package com.example.obscura_backend.controller;

import com.example.obscura_backend.dto.PhotoMetadataDto;
import com.example.obscura_backend.dto.PhotoResponseDto;
import com.example.obscura_backend.dto.BatchUploadResultDto;
import com.example.obscura_backend.mapper.PhotoMapper;
import com.example.obscura_backend.model.Photo;
import com.example.obscura_backend.service.PhotoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/photos")
@CrossOrigin
@Tag(name = "Photos", description = "Photo management endpoints")
public class PhotoController {

    private static final Logger logger = LoggerFactory.getLogger(PhotoController.class);

    private final PhotoService photoService;
    private final PhotoMapper photoMapper;

    public PhotoController(PhotoService photoService, PhotoMapper photoMapper) {
        this.photoService = photoService;
        this.photoMapper = photoMapper;
    }

    @GetMapping
    @Operation(summary = "Get all photos", description = "Retrieves a list of all photos with their metadata")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Successfully retrieved photos",
                content = @Content(mediaType = "application/json",
                        schema = @Schema(implementation = PhotoMetadataDto.class))),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<?> getAllPhotos() {
        try {
            List<Photo> photos = photoService.getAllPhotos();
            List<PhotoMetadataDto> photoDtos = photos.stream()
                    .map(photoMapper::toMetadataDto)
                    .collect(Collectors.toList());

            return ResponseEntity.ok(photoDtos);

        } catch (Exception e) {
            logger.error("Error retrieving photos: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to retrieve photos"));
        }
    }

    @GetMapping("/search")
    @Operation(summary = "Search photos", description = "Search photos by camera make, model, lens, RAW status, and date range")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Search completed successfully",
                content = @Content(mediaType = "application/json",
                        schema = @Schema(implementation = PhotoMetadataDto.class))),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<?> searchPhotos(
            @RequestParam(required = false) String make,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String lens,
            @RequestParam(required = false) Boolean isRaw,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate) {
        try {
            logger.info("Searching photos with filters - make: {}, model: {}, lens: {}, isRaw: {}, startDate: {}, endDate: {}",
                       make, model, lens, isRaw, startDate, endDate);

            List<Photo> photos = photoService.searchPhotos(make, model, lens, isRaw, startDate, endDate);
            List<PhotoMetadataDto> photoDtos = photos.stream()
                    .map(photoMapper::toMetadataDto)
                    .collect(Collectors.toList());

            logger.info("Search found {} photo(s)", photoDtos.size());
            return ResponseEntity.ok(photoDtos);

        } catch (Exception e) {
            logger.error("Error searching photos: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to search photos"));
        }
    }

    @PostMapping("/upload")
    @Operation(summary = "Upload a photo", description = "Uploads a photo file (JPEG or RAW format) and extracts metadata")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "201", description = "Photo uploaded successfully",
                content = @Content(mediaType = "application/json",
                        schema = @Schema(implementation = PhotoResponseDto.class))),
        @ApiResponse(responseCode = "400", description = "Bad request - invalid file or no file provided"),
        @ApiResponse(responseCode = "413", description = "File size exceeds limit"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<Object> uploadPhoto(
            @RequestParam(value = "file", required = false) MultipartFile file,
            HttpServletRequest request) {
        try {
            if (file == null && request instanceof MultipartHttpServletRequest) {
                MultipartHttpServletRequest multipartRequest = (MultipartHttpServletRequest) request;

                if (multipartRequest.getFile("photo") != null) {
                    file = multipartRequest.getFile("photo");
                } else if (multipartRequest.getFile("image") != null) {
                    file = multipartRequest.getFile("image");
                }
            }

            if (file == null) {
                logger.error("No file provided in upload request");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("error", "No file provided. Please select a file to upload. Parameter name must be 'file'."));
            }

            if (file.isEmpty()) {
                logger.error("Empty file provided");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("error", "The uploaded file is empty."));
            }


            Photo photo = photoService.savePhoto(file);
            PhotoResponseDto response = photoMapper.toDto(photo);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);

        } catch (IllegalArgumentException e) {
            logger.error("Validation error: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));

        } catch (MultipartException e) {
            logger.error("File size exceeds limit: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(Map.of("error", "The uploaded file exceeds the maximum allowed size."));

        } catch (IOException e) {
            logger.error("IO error processing file: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to process the file."));

        } catch (Exception e) {
            logger.error("Unexpected error: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "An unexpected error occurred."));
        }
    }

    @PostMapping("/upload/batch")
    @Operation(summary = "Upload multiple photos", description = "Uploads multiple photo files with duplicate detection, parallel processing, and error recovery")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "201", description = "Batch upload completed",
                content = @Content(mediaType = "application/json",
                        schema = @Schema(implementation = BatchUploadResultDto.class))),
        @ApiResponse(responseCode = "400", description = "Bad request - invalid files or no files provided"),
        @ApiResponse(responseCode = "413", description = "File size exceeds limit"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<Object> uploadPhotos(@RequestParam("files") List<MultipartFile> files) {
        try {
            if (files == null || files.isEmpty()) {
                logger.error("No files provided in batch upload request");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("error", "No files provided. Please select files to upload."));
            }

            logger.info("Batch upload request received with {} file(s)", files.size());

            BatchUploadResultDto result = photoService.savePhotosBatch(files);

            HttpStatus status = result.getSuccessCount() > 0 ? HttpStatus.CREATED : HttpStatus.BAD_REQUEST;

            return ResponseEntity.status(status).body(result);

        } catch (IllegalArgumentException e) {
            logger.error("Validation error: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));

        } catch (MultipartException e) {
            logger.error("File size exceeds limit: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(Map.of("error", "One or more uploaded files exceed the maximum allowed size."));

        } catch (Exception e) {
            logger.error("Unexpected error: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "An unexpected error occurred: " + e.getMessage()));
        }
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get photo by ID", description = "Retrieves a single photo with its metadata by ID")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Photo found",
                content = @Content(mediaType = "application/json",
                        schema = @Schema(implementation = PhotoMetadataDto.class))),
        @ApiResponse(responseCode = "404", description = "Photo not found"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<?> getPhotoById(@PathVariable Long id) {
        try {
            Photo photo = photoService.getPhotoById(id);
            PhotoMetadataDto photoDto = photoMapper.toMetadataDto(photo);
            return ResponseEntity.ok(photoDto);
        } catch (RuntimeException e) {
            logger.error("Photo not found: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Error retrieving photo: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to retrieve photo"));
        }
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete photo", description = "Deletes a photo and its associated file from storage")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "204", description = "Photo deleted successfully"),
        @ApiResponse(responseCode = "404", description = "Photo not found"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<?> deletePhoto(@PathVariable Long id) {
        try {
            photoService.deletePhoto(id);
            logger.info("Successfully deleted photo with id: {}", id);
            return ResponseEntity.noContent().build();
        } catch (RuntimeException e) {
            logger.error("Photo not found: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Error deleting photo: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to delete photo"));
        }
    }

    @DeleteMapping("/batch")
    @Operation(summary = "Delete multiple photos", description = "Deletes multiple photos by their IDs")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Batch delete completed"),
        @ApiResponse(responseCode = "400", description = "Bad request - no IDs provided"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<?> deletePhotos(@RequestParam("ids") List<Long> ids) {
        try {
            if (ids == null || ids.isEmpty()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("error", "No photo IDs provided"));
            }

            logger.info("Batch delete request for {} photo(s)", ids.size());

            int deletedCount = 0;
            List<String> errors = new ArrayList<>();

            for (Long id : ids) {
                try {
                    photoService.deletePhoto(id);
                    deletedCount++;
                } catch (Exception e) {
                    errors.add("Failed to delete photo " + id + ": " + e.getMessage());
                    logger.error("Failed to delete photo {}: {}", id, e.getMessage());
                }
            }

            return ResponseEntity.ok(Map.of(
                "deletedCount", deletedCount,
                "requestedCount", ids.size(),
                "errors", errors
            ));

        } catch (Exception e) {
            logger.error("Error in batch delete: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to delete photos"));
        }
    }
}
