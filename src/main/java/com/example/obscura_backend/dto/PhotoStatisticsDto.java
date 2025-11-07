package com.example.obscura_backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PhotoStatisticsDto {

    private Integer totalPhotos;
    private Integer totalRawPhotos;
    private Integer totalJpegPhotos;
    private Long totalStorageUsedMB;

    private String mostUsedCamera;
    private Map<String, Integer> cameraUsage;
    private String mostUsedLens;
    private Map<String, Integer> lensUsage;

    private String mostUsedAperture;
    private Map<String, Integer> apertureDistribution;
    private String mostUsedIso;
    private Map<String, Integer> isoDistribution;
    private String mostUsedFocalLength;
    private Map<String, Integer> focalLengthDistribution;

    private Integer photosWithGps;
    private Integer photosWithoutGps;
    private Double averageLatitude;
    private Double averageLongitude;

    private String uploadPeriod;
    private Integer photosLastWeek;
    private Integer photosLastMonth;
    private String mostActiveDay;

    private List<String> insights;
    private List<String> recommendations;
}