package com.example.obscura_backend.mapper;

import com.example.obscura_backend.dto.PhotoResponseDto;
import com.example.obscura_backend.model.Photo;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;

@Component
public class PhotoMapper {

    private final DateTimeFormatter formatter = DateTimeFormatter.ISO_DATE_TIME;

    public PhotoResponseDto toDto(Photo photo) {
        PhotoResponseDto dto = new PhotoResponseDto();
        dto.setId(photo.getId());
        dto.setFileName(photo.getFileName());
        dto.setContentType(photo.getContentType());
        dto.setFileSize(photo.getFileSize());
        dto.setUploadedAt(photo.getUploadedAt().format(formatter));
        dto.setMetadata(photo.getMetadata());
        return dto;
    }
}
