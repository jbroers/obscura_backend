package com.example.obscura_backend.unit.controller;

import com.example.obscura_backend.controller.PhotoController;
import com.example.obscura_backend.service.PhotoService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PhotoController.class)
class PhotoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PhotoService photoService;

    @Test
    void uploadPhoto_success() throws Exception {
        MockMultipartFile mockFile = new MockMultipartFile(
                "photo",
                "test.jpg",
                "image/jpeg",
                "fake-image-data".getBytes()
        );

        Mockito.doNothing().when(photoService).savePhoto(any());

        mockMvc.perform(multipart("/photos").file(mockFile))
                .andExpect(status().isOk())
                .andExpect(content().string("Uploaded"));
    }

    @Test
    void uploadPhoto_failure() throws Exception {
        MockMultipartFile mockFile = new MockMultipartFile(
                "photo",
                "bad.jpg",
                "image/jpeg",
                "fake-image-data".getBytes()
        );

        doThrow(new RuntimeException("Disk error")).when(photoService).savePhoto(any());
        mockMvc.perform(multipart("/photos").file(mockFile))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string("Upload failed"));
    }
}
