package com.example.obscura_backend.unit.service;

import com.example.obscura_backend.dto.PhotoStatisticsDto;
import com.example.obscura_backend.model.Photo;
import com.example.obscura_backend.repository.PhotoRepository;
import com.example.obscura_backend.service.StatisticsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StatisticsServiceTest {

    @Mock
    private PhotoRepository photoRepository;

    @InjectMocks
    private StatisticsService statisticsService;

    private List<Photo> testPhotos;

    @BeforeEach
    void setup() {
        testPhotos = Arrays.asList(
                Photo.builder()
                        .id(1L)
                        .fileName("photo1.jpg")
                        .fileSize(2048000L)
                        .isRaw(false)
                        .uploadedAt(LocalDateTime.now())
                        .cameraMake("Canon")
                        .cameraModel("EOS R5")
                        .lensModel("RF 24-70mm f/2.8")
                        .iso(400)
                        .aperture("f/2.8")
                        .shutterSpeed("1/250")
                        .focalLength("50 mm")
                        .gpsLatitude("52.3676")
                        .gpsLongitude("4.9041")
                        .build(),
                Photo.builder()
                        .id(2L)
                        .fileName("photo2.jpg")
                        .fileSize(3072000L)
                        .isRaw(true)
                        .uploadedAt(LocalDateTime.now().minusDays(1))
                        .cameraMake("Sony")
                        .cameraModel("A7IV")
                        .lensModel("FE 24-105mm f/4")
                        .iso(800)
                        .aperture("f/4.0")
                        .shutterSpeed("1/125")
                        .focalLength("85 mm")
                        .gpsLatitude("51.9244")
                        .gpsLongitude("4.4777")
                        .build(),
                Photo.builder()
                        .id(3L)
                        .fileName("photo3.jpg")
                        .fileSize(2560000L)
                        .isRaw(false)
                        .uploadedAt(LocalDateTime.now().minusWeeks(2))
                        .cameraMake("Canon")
                        .cameraModel("EOS R5")
                        .lensModel("RF 24-70mm f/2.8")
                        .iso(400)
                        .aperture("f/2.8")
                        .shutterSpeed("1/500")
                        .focalLength("35 mm")
                        .build()
        );
    }

    @Test
    void getPhotoStatistics_withPhotos_returnsCorrectStatistics() {
        when(photoRepository.findAll()).thenReturn(testPhotos);

        PhotoStatisticsDto stats = statisticsService.getPhotoStatistics();

        assertNotNull(stats);
        assertEquals(3, stats.getTotalPhotos());
        assertEquals(1, stats.getTotalRawPhotos());
        assertEquals(2, stats.getTotalJpegPhotos());
        assertTrue(stats.getTotalStorageUsedMB() > 0);
    }

    @Test
    void getPhotoStatistics_calculatesCorrectCameraUsage() {
        when(photoRepository.findAll()).thenReturn(testPhotos);

        PhotoStatisticsDto stats = statisticsService.getPhotoStatistics();

        assertNotNull(stats.getCameraUsage());
        assertEquals(2, stats.getCameraUsage().size());
        assertEquals("Canon EOS R5", stats.getMostUsedCamera());
        assertEquals(2, stats.getCameraUsage().get("Canon EOS R5"));
        assertEquals(1, stats.getCameraUsage().get("Sony A7IV"));
    }

    @Test
    void getPhotoStatistics_calculatesCorrectLensUsage() {
        when(photoRepository.findAll()).thenReturn(testPhotos);

        PhotoStatisticsDto stats = statisticsService.getPhotoStatistics();

        assertNotNull(stats.getLensUsage());
        assertEquals(2, stats.getLensUsage().size());
        assertEquals("RF 24-70mm f/2.8", stats.getMostUsedLens());
    }

    @Test
    void getPhotoStatistics_calculatesCorrectExifDistribution() {
        when(photoRepository.findAll()).thenReturn(testPhotos);

        PhotoStatisticsDto stats = statisticsService.getPhotoStatistics();

        assertNotNull(stats.getApertureDistribution());
        assertEquals("f/2.8", stats.getMostUsedAperture());
        assertNotNull(stats.getIsoDistribution());
        assertEquals("400", stats.getMostUsedIso());
    }

    @Test
    void getPhotoStatistics_calculatesCorrectGpsStatistics() {
        when(photoRepository.findAll()).thenReturn(testPhotos);

        PhotoStatisticsDto stats = statisticsService.getPhotoStatistics();

        assertEquals(2, stats.getPhotosWithGps());
        assertEquals(1, stats.getPhotosWithoutGps());
        assertNotNull(stats.getAverageLatitude());
        assertNotNull(stats.getAverageLongitude());
    }

    @Test
    void getPhotoStatistics_calculatesCorrectTimeStatistics() {
        when(photoRepository.findAll()).thenReturn(testPhotos);

        PhotoStatisticsDto stats = statisticsService.getPhotoStatistics();

        assertEquals(2, stats.getPhotosLastWeek());
        assertEquals(3, stats.getPhotosLastMonth());
        assertNotNull(stats.getMostActiveDay());
    }

    @Test
    void getPhotoStatistics_generatesInsights() {
        when(photoRepository.findAll()).thenReturn(testPhotos);

        PhotoStatisticsDto stats = statisticsService.getPhotoStatistics();

        assertNotNull(stats.getInsights());
        assertFalse(stats.getInsights().isEmpty());
    }

    @Test
    void getPhotoStatistics_generatesRecommendations() {
        when(photoRepository.findAll()).thenReturn(testPhotos);

        PhotoStatisticsDto stats = statisticsService.getPhotoStatistics();

        assertNotNull(stats.getRecommendations());
        assertFalse(stats.getRecommendations().isEmpty());
    }

    @Test
    void getPhotoStatistics_withNoPhotos_returnsEmptyStatistics() {
        when(photoRepository.findAll()).thenReturn(Arrays.asList());

        PhotoStatisticsDto stats = statisticsService.getPhotoStatistics();

        assertNotNull(stats);
        assertEquals(0, stats.getTotalPhotos());
        assertEquals(0, stats.getTotalRawPhotos());
        assertEquals(0, stats.getTotalJpegPhotos());
        assertEquals(0L, stats.getTotalStorageUsedMB());
        assertNotNull(stats.getInsights());
        assertTrue(stats.getInsights().contains("Nog geen foto's geüpload"));
    }

    @Test
    void getPhotoStatistics_withPhotosWithoutMetadata_handlesGracefully() {
        List<Photo> minimalPhotos = Arrays.asList(
                Photo.builder()
                        .id(1L)
                        .fileName("minimal.jpg")
                        .fileSize(1024000L)
                        .isRaw(false)
                        .uploadedAt(LocalDateTime.now())
                        .build()
        );

        when(photoRepository.findAll()).thenReturn(minimalPhotos);

        PhotoStatisticsDto stats = statisticsService.getPhotoStatistics();

        assertNotNull(stats);
        assertEquals(1, stats.getTotalPhotos());
        assertNotNull(stats.getCameraUsage());
        assertNotNull(stats.getLensUsage());
        assertNotNull(stats.getApertureDistribution());
    }
}

