package com.example.obscura_backend.controller;

import com.example.obscura_backend.dto.PhotoResponseDto;
import com.example.obscura_backend.mapper.PhotoMapper;
import com.example.obscura_backend.model.Photo;
import com.example.obscura_backend.service.PhotoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

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

    @PostMapping("/upload")
    public ResponseEntity<PhotoResponseDto> uploadPhoto(@RequestParam("file") MultipartFile file) {
        try {
            logger.info("Received upload request: {} ({} bytes)",
                    file.getOriginalFilename(), file.getSize());

            Photo photo = photoService.savePhoto(file);
            PhotoResponseDto response = photoMapper.toDto(photo);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);

        } catch (IllegalArgumentException e) {
            logger.error("Validation error: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();

        } catch (IOException e) {
            logger.error("IO error processing file: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();

        } catch (Exception e) {
            logger.error("Unexpected error: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
