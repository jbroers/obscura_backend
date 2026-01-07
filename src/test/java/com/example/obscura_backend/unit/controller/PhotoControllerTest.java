package com.example.obscura_backend.unit.controller;

import com.example.obscura_backend.controller.PhotoController;
import com.example.obscura_backend.dto.PhotoMetadataDto;
import com.example.obscura_backend.dto.PhotoResponseDto;
import com.example.obscura_backend.mapper.PhotoMapper;
import com.example.obscura_backend.model.Photo;
import com.example.obscura_backend.service.PhotoService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = PhotoController.class, excludeFilters = @ComponentScan.Filter(
    type = FilterType.ASSIGNABLE_TYPE,
    classes = {com.example.obscura_backend.filter.ContentLengthFilter.class}
))
@TestPropertySource(properties = {
    "server.base-url=http://localhost:8080"
})
class PhotoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PhotoService photoService;

    @MockitoBean
    private PhotoMapper photoMapper;

    @Test
    void uploadPhoto_success() throws Exception {
        MockMultipartFile mockFile = new MockMultipartFile(
                "file",
                "test.jpg",
                "image/jpeg",
                "fake-image-data".getBytes()
        );

        Photo mockPhoto = Photo.builder()
                .id(1L)
                .fileName("test.jpg")
                .filePath("/uploads/test.jpg")
                .contentType("image/jpeg")
                .build();

        PhotoResponseDto mockResponse = PhotoResponseDto.builder()
                .id(1L)
                .fileName("test.jpg")
                .url("http://localhost:8080/uploads/test.jpg")
                .build();

        when(photoService.savePhoto(any())).thenReturn(mockPhoto);
        when(photoMapper.toDto(any())).thenReturn(mockResponse);

        mockMvc.perform(multipart("/photos/upload").file(mockFile))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.fileName").value("test.jpg"));

        verify(photoService, times(1)).savePhoto(any());
        verify(photoMapper, times(1)).toDto(any());
    }

    @Test
    void uploadPhoto_noFileProvided() throws Exception {
        mockMvc.perform(multipart("/photos/upload"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());

        verify(photoService, never()).savePhoto(any());
    }

    @Test
    void uploadPhoto_invalidFileType() throws Exception {
        MockMultipartFile mockFile = new MockMultipartFile(
                "file",
                "test.txt",
                "text/plain",
                "not an image".getBytes()
        );

        when(photoService.savePhoto(any())).thenThrow(new IllegalArgumentException("Uploaded file is not a valid image format!"));

        mockMvc.perform(multipart("/photos/upload").file(mockFile))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Uploaded file is not a valid image format!"));

        verify(photoService, times(1)).savePhoto(any());
    }

    @Test
    void uploadPhoto_ioException() throws Exception {
        MockMultipartFile mockFile = new MockMultipartFile(
                "file",
                "test.jpg",
                "image/jpeg",
                "fake-image-data".getBytes()
        );

        when(photoService.savePhoto(any())).thenThrow(new java.io.IOException("Disk full"));

        mockMvc.perform(multipart("/photos/upload").file(mockFile))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("Failed to process the file."));

        verify(photoService, times(1)).savePhoto(any());
    }

    @Test
    void getAllPhotos_success() throws Exception {
        Photo photo1 = Photo.builder()
                .id(1L)
                .fileName("photo1.jpg")
                .filePath("/uploads/photo1.jpg")
                .iso(400)
                .aperture("f/2.8")
                .build();

        Photo photo2 = Photo.builder()
                .id(2L)
                .fileName("photo2.jpg")
                .filePath("/uploads/photo2.jpg")
                .iso(800)
                .aperture("f/1.8")
                .build();

        PhotoMetadataDto dto1 = PhotoMetadataDto.builder()
                .id(1L)
                .filename("photo1.jpg")
                .url("http://localhost:8080/uploads/photo1.jpg")
                .iso("400")
                .aperture("f/2.8")
                .build();

        PhotoMetadataDto dto2 = PhotoMetadataDto.builder()
                .id(2L)
                .filename("photo2.jpg")
                .url("http://localhost:8080/uploads/photo2.jpg")
                .iso("800")
                .aperture("f/1.8")
                .build();

        List<Photo> mockPhotos = Arrays.asList(photo1, photo2);
        when(photoService.getAllPhotos()).thenReturn(mockPhotos);
        when(photoMapper.toMetadataDto(photo1)).thenReturn(dto1);
        when(photoMapper.toMetadataDto(photo2)).thenReturn(dto2);

        mockMvc.perform(get("/photos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].filename").value("photo1.jpg"))
                .andExpect(jsonPath("$[1].id").value(2))
                .andExpect(jsonPath("$[1].filename").value("photo2.jpg"));

        verify(photoService, times(1)).getAllPhotos();
        verify(photoMapper, times(2)).toMetadataDto(any());
    }

    @Test
    void getAllPhotos_emptyList() throws Exception {
        when(photoService.getAllPhotos()).thenReturn(Arrays.asList());

        mockMvc.perform(get("/photos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());

        verify(photoService, times(1)).getAllPhotos();
    }

    @Test
    void getAllPhotos_serviceException() throws Exception {
        when(photoService.getAllPhotos()).thenThrow(new RuntimeException("Database error"));

        mockMvc.perform(get("/photos"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("Failed to retrieve photos"));

        verify(photoService, times(1)).getAllPhotos();
    }
}
