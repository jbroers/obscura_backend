package com.example.obscura_backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PhotoMetadataDto {
    private Long id;
    private String filename;
    private String url;
    private Long fileSize;
    private String aperture;
    private String shutterSpeed;
    private String iso;
    private String gpsLatitude;
    private String gpsLongitude;
    private String cameraMake;
    private String cameraModel;
    private String lensModel;
    private String focalLength;
    private String dateTaken;
    private String resolution;
}

