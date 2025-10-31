package com.example.obscura_backend.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "photos")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Photo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String fileName;
    private String filePath;
    private String contentType;
    private Long fileSize;
    private LocalDateTime uploadedAt;
    private Boolean isRaw;

    @Column(length = 10000)
    private String metadata;

    private String cameraMake;
    private String cameraModel;
    private String lensModel;
    private Integer iso;
    private String aperture;
    private String shutterSpeed;
    private String focalLength;
    private String exposureCompensation;
    private String whiteBalance;
    private String meteringMode;
    private String flashMode;

    private Integer width;
    private Integer height;
    private LocalDateTime dateTaken;
    private String orientation;
}
