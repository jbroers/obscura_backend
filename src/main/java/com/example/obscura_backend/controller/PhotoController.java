package com.example.obscura_backend.controller;

import com.example.obscura_backend.dto.PhotoMetadataDto;
import com.example.obscura_backend.dto.PhotoResponseDto;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import java.io.IOException;
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
    @Operation(summary = "Upload multiple photos", description = "Uploads multiple photo files (JPEG or RAW format) and extracts metadata")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "201", description = "Photos uploaded successfully"),
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

            List<Photo> photos = photoService.savePhotos(files);
            List<PhotoResponseDto> responses = photos.stream()
                    .map(photoMapper::toDto)
                    .collect(Collectors.toList());

            logger.info("Batch upload successful: {} photo(s) uploaded", responses.size());

            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "message", "Successfully uploaded " + responses.size() + " photo(s)",
                "photos", responses
            ));

        } catch (IllegalArgumentException e) {
            logger.error("Validation error: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));

        } catch (MultipartException e) {
            logger.error("File size exceeds limit: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(Map.of("error", "One or more uploaded files exceed the maximum allowed size."));

        } catch (IOException e) {
            logger.error("IO error processing files: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to process one or more files: " + e.getMessage()));

        } catch (Exception e) {
            logger.error("Unexpected error: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "An unexpected error occurred."));
        }
    }
}
