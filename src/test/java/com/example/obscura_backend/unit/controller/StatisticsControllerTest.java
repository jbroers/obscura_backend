package com.example.obscura_backend.unit.controller;

import com.example.obscura_backend.controller.StatisticsController;
import com.example.obscura_backend.dto.PhotoStatisticsDto;
import com.example.obscura_backend.service.StatisticsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;
import java.util.HashMap;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = StatisticsController.class, excludeFilters = @ComponentScan.Filter(
    type = FilterType.ASSIGNABLE_TYPE,
    classes = {com.example.obscura_backend.filter.ContentLengthFilter.class}
))
@TestPropertySource(properties = {
    "server.base-url=http://localhost:8080"
})
class StatisticsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StatisticsService statisticsService;

    @Test
    void getPhotoStatistics_success() throws Exception {
        PhotoStatisticsDto mockStats = PhotoStatisticsDto.builder()
                .totalPhotos(10)
                .totalRawPhotos(5)
                .totalJpegPhotos(5)
                .totalStorageUsedMB(50L)
                .mostUsedCamera("Canon EOS R5")
                .cameraUsage(new HashMap<>())
                .mostUsedLens("RF 24-70mm f/2.8")
                .lensUsage(new HashMap<>())
                .mostUsedAperture("f/2.8")
                .apertureDistribution(new HashMap<>())
                .mostUsedIso("400")
                .isoDistribution(new HashMap<>())
                .mostUsedFocalLength("50 mm")
                .focalLengthDistribution(new HashMap<>())
                .photosWithGps(8)
                .photosWithoutGps(2)
                .averageLatitude(52.0)
                .averageLongitude(4.5)
                .uploadPeriod("30 dagen")
                .photosLastWeek(5)
                .photosLastMonth(10)
                .mostActiveDay("Zaterdag")
                .insights(Arrays.asList("Je fotografeert vaak met f/2.8"))
                .recommendations(Arrays.asList("Experimenteer met verschillende lenzen"))
                .build();

        when(statisticsService.getPhotoStatistics()).thenReturn(mockStats);

        mockMvc.perform(get("/statistics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalPhotos").value(10))
                .andExpect(jsonPath("$.totalRawPhotos").value(5))
                .andExpect(jsonPath("$.totalJpegPhotos").value(5))
                .andExpect(jsonPath("$.mostUsedCamera").value("Canon EOS R5"))
                .andExpect(jsonPath("$.mostUsedLens").value("RF 24-70mm f/2.8"))
                .andExpect(jsonPath("$.mostUsedAperture").value("f/2.8"))
                .andExpect(jsonPath("$.photosWithGps").value(8))
                .andExpect(jsonPath("$.insights").isArray())
                .andExpect(jsonPath("$.recommendations").isArray());

        verify(statisticsService, times(1)).getPhotoStatistics();
    }

    @Test
    void getPhotoStatistics_serviceException() throws Exception {
        when(statisticsService.getPhotoStatistics()).thenThrow(new RuntimeException("Database error"));

        mockMvc.perform(get("/statistics"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("Failed to calculate statistics"));

        verify(statisticsService, times(1)).getPhotoStatistics();
    }

    @Test
    void getPhotoStatistics_emptyDatabase() throws Exception {
        PhotoStatisticsDto emptyStats = PhotoStatisticsDto.builder()
                .totalPhotos(0)
                .totalRawPhotos(0)
                .totalJpegPhotos(0)
                .totalStorageUsedMB(0L)
                .cameraUsage(new HashMap<>())
                .lensUsage(new HashMap<>())
                .apertureDistribution(new HashMap<>())
                .isoDistribution(new HashMap<>())
                .focalLengthDistribution(new HashMap<>())
                .photosWithGps(0)
                .photosWithoutGps(0)
                .uploadPeriod("N/A")
                .photosLastWeek(0)
                .photosLastMonth(0)
                .insights(Arrays.asList("Nog geen foto's geüpload"))
                .recommendations(Arrays.asList("Upload je eerste foto om statistieken te zien"))
                .build();

        when(statisticsService.getPhotoStatistics()).thenReturn(emptyStats);

        mockMvc.perform(get("/statistics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalPhotos").value(0))
                .andExpect(jsonPath("$.insights[0]").value("Nog geen foto's geüpload"));

        verify(statisticsService, times(1)).getPhotoStatistics();
    }
}

