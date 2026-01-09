package com.example.obscura_backend.mapper;

import com.example.obscura_backend.dto.PhotoMetadataDto;
import com.example.obscura_backend.dto.PhotoResponseDto;
import com.example.obscura_backend.model.Photo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.format.DateTimeFormatter;

@Component
public class PhotoMapper {

    private final DateTimeFormatter formatter = DateTimeFormatter.ISO_DATE_TIME;

    @Value("${server.base-url}")
    private String baseUrl;

    public PhotoResponseDto toDto(Photo photo) {
        PhotoResponseDto dto = new PhotoResponseDto();
        dto.setId(photo.getId());
        dto.setFileName(photo.getFileName());
        dto.setUrl(photo.getFileUrl() != null ? photo.getFileUrl() : generatePhotoUrl(photo.getFilePath()));
        dto.setContentType(photo.getContentType());
        dto.setFileSize(photo.getFileSize());
        dto.setUploadedAt(photo.getUploadedAt().format(formatter));
        dto.setIsRaw(photo.getIsRaw());
        return dto;
    }

    public PhotoMetadataDto toMetadataDto(Photo photo) {
        PhotoMetadataDto dto = new PhotoMetadataDto();
        dto.setId(photo.getId());
        dto.setFilename(photo.getFileName());

        String url = photo.getFileUrl() != null ? photo.getFileUrl() : generatePhotoUrl(photo.getFilePath());
        dto.setUrl(url);

        dto.setAperture(photo.getAperture());
        dto.setShutterSpeed(photo.getShutterSpeed());
        dto.setIso(photo.getIso() != null ? photo.getIso().toString() : null);

        dto.setGpsLatitude(photo.getGpsLatitude());
        dto.setGpsLongitude(photo.getGpsLongitude());

        dto.setCameraMake(photo.getCameraMake());
        dto.setCameraModel(photo.getCameraModel());
        dto.setLensModel(photo.getLensModel());
        dto.setFocalLength(photo.getFocalLength());

        dto.setResolution(photo.getResolution());
        dto.setDateTaken(photo.getDateTaken() != null ? photo.getDateTaken().format(formatter) : null);

        return dto;
    }

    private String generatePhotoUrl(String filePath) {
        if (filePath == null) {
            return null;
        }

        Path path = Paths.get(filePath);
        String filename = path.getFileName().toString();

        return baseUrl + "/uploads/" + filename;
    }
}
