package com.example.obscura_backend.integration;

import com.example.obscura_backend.repository.PhotoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Paths;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class PhotoControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PhotoRepository photoRepository;

    @AfterEach
    void cleanUp() {
        photoRepository.deleteAll();
    }

    @Test
    void uploadPhotoShouldReturnCreated() throws Exception {
        byte[] testImageBytes = Files.readAllBytes(Paths.get("src/test/resources/test.jpg"));

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test.jpg",
                "image/jpeg",
                testImageBytes
        );

        mockMvc.perform(multipart("/photos/upload").file(file))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.fileName").value(containsString("test.jpg")))
                .andExpect(jsonPath("$.url").exists());
    }

    @Test
    void uploadInvalidPhotoShouldFail() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test.txt",
                "text/plain",
                "hello".getBytes()
        );

        mockMvc.perform(multipart("/photos/upload").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("not a valid image")));
    }

    @Test
    void uploadEmptyFileShouldFail() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "empty.jpg",
                "image/jpeg",
                new byte[0]
        );

        mockMvc.perform(multipart("/photos/upload").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void getAllPhotosIntegrationTest() throws Exception {
        byte[] testImageBytes = Files.readAllBytes(Paths.get("src/test/resources/test.jpg"));

        MockMultipartFile file1 = new MockMultipartFile(
                "file",
                "photo1.jpg",
                "image/jpeg",
                testImageBytes
        );

        MockMultipartFile file2 = new MockMultipartFile(
                "file",
                "photo2.jpg",
                "image/jpeg",
                testImageBytes
        );

        mockMvc.perform(multipart("/photos/upload").file(file1))
                .andExpect(status().isCreated());

        mockMvc.perform(multipart("/photos/upload").file(file2))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/photos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").exists())
                .andExpect(jsonPath("$[0].filename").exists())
                .andExpect(jsonPath("$[0].url").exists())
                .andExpect(jsonPath("$[1].id").exists())
                .andExpect(jsonPath("$[1].filename").exists())
                .andExpect(jsonPath("$[1].url").exists());
    }

    @Test
    void getAllPhotosWhenEmptyShouldReturnEmptyArray() throws Exception {
        mockMvc.perform(get("/photos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$", hasSize(0)));
    }
}
