package com.example.obscura_backend.controller;

import com.example.obscura_backend.dto.PhotoMetadataDto;
import com.example.obscura_backend.dto.PhotoResponseDto;
import com.example.obscura_backend.mapper.PhotoMapper;
import com.example.obscura_backend.model.Photo;
import com.example.obscura_backend.service.PhotoService;
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
import java.util.stream.Collectors;

@RestController
@RequestMapping("/photos")
@CrossOrigin
public class PhotoController {

    private static final Logger logger = LoggerFactory.getLogger(PhotoController.class);

    private final PhotoService photoService;
    private final PhotoMapper photoMapper;

    public PhotoController(PhotoService photoService, PhotoMapper photoMapper) {
        this.photoService = photoService;
        this.photoMapper = photoMapper;
    }

    @GetMapping
    public ResponseEntity<?> getAllPhotos() {
        try {
            logger.info("Fetching all photos");

            List<Photo> photos = photoService.getAllPhotos();
            List<PhotoMetadataDto> photoDtos = photos.stream()
                    .map(photoMapper::toMetadataDto)
                    .collect(Collectors.toList());

            logger.info("Successfully retrieved {} photos", photoDtos.size());
            return ResponseEntity.ok(photoDtos);

        } catch (Exception e) {
            logger.error("Error retrieving photos: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("{\"error\": \"Failed to retrieve photos\", \"message\": \"" + e.getMessage() + "\"}");
        }
    }

    @PostMapping("/upload")
    public ResponseEntity<?> uploadPhoto(
            @RequestParam(value = "file", required = false) MultipartFile file,
            HttpServletRequest request) {
        try {
            logger.info("Received upload request");
            logger.info("Request content type: {}", request.getContentType());
            logger.info("Request content length: {}", request.getContentLength());

            logger.info("All parameter names: {}", Collections.list(request.getParameterNames()));

            if (file == null && request instanceof MultipartHttpServletRequest) {
                MultipartHttpServletRequest multipartRequest = (MultipartHttpServletRequest) request;
                logger.info("Available file parameter names: {}", multipartRequest.getFileMap().keySet());

                if (multipartRequest.getFile("photo") != null) {
                    file = multipartRequest.getFile("photo");
                    logger.info("Found file under 'photo' parameter");
                } else if (multipartRequest.getFile("image") != null) {
                    file = multipartRequest.getFile("image");
                    logger.info("Found file under 'image' parameter");
                }
            }

            if (file == null) {
                logger.error("No file provided in request - checked 'file', 'photo', and 'image' parameters");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body("{\"error\": \"No file provided. Please select a file to upload. Parameter name must be 'file'.\"}");
            }

            if (file.isEmpty()) {
                logger.error("Empty file provided");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body("{\"error\": \"The uploaded file is empty.\"}");
            }

            logger.info("Processing file: {} ({} bytes)",
                    file.getOriginalFilename(), file.getSize());

            Photo photo = photoService.savePhoto(file);
            PhotoResponseDto response = photoMapper.toDto(photo);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);

        } catch (IllegalArgumentException e) {
            logger.error("Validation error: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("{\"error\": \"" + e.getMessage() + "\"}");

        } catch (MultipartException e) {
            logger.error("File size exceeds limit: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body("{\"error\": \"The uploaded file exceeds the maximum allowed size.\"}");

        } catch (IOException e) {
            logger.error("IO error processing file: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("{\"error\": \"Failed to process the file.\"}");

        } catch (Exception e) {
            logger.error("Unexpected error: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("{\"error\": \"An unexpected error occurred.\"}");
        }
    }
}
