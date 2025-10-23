package com.example.obscura_backend.integration;

import com.example.obscura_backend.service.PhotoService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class PhotoControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PhotoService photoService;

    @Test
    void uploadPhotoShouldReturnOk() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "photo",
                "test.jpg",
                "image/jpeg",
                getClass().getResourceAsStream("/test.jpg")
        );

        mockMvc.perform(multipart("/photos").file(file))
                .andExpect(status().isOk())
                .andExpect(content().string("Uploaded"));
    }

    @Test
    void uploadInvalidPhotoShouldFail() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "photo",
                "test.txt",
                "text/plain",
                "hello".getBytes()
        );

        mockMvc.perform(multipart("/photos").file(file))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string("Upload failed"));
    }
}
