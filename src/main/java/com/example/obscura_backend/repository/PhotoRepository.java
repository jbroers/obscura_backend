package com.example.obscura_backend.repository;

import com.example.obscura_backend.model.Photo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface PhotoRepository extends JpaRepository<Photo, Long> {
    Optional<Photo> findByFileHash(String fileHash);
    boolean existsByFileHash(String fileHash);

    List<Photo> findByCameraMakeContainingIgnoreCase(String make);
    List<Photo> findByCameraModelContainingIgnoreCase(String model);
    List<Photo> findByLensModelContainingIgnoreCase(String lens);
    List<Photo> findByFileNameContainingIgnoreCase(String fileName);
    List<Photo> findByDateTakenBetween(LocalDateTime start, LocalDateTime end);
    List<Photo> findByIsRaw(Boolean isRaw);

    @Query("SELECT p FROM Photo p WHERE " +
           "(:make IS NULL OR LOWER(p.cameraMake) LIKE LOWER(CONCAT('%', :make, '%'))) AND " +
           "(:model IS NULL OR LOWER(p.cameraModel) LIKE LOWER(CONCAT('%', :model, '%'))) AND " +
           "(:lens IS NULL OR LOWER(p.lensModel) LIKE LOWER(CONCAT('%', :lens, '%'))) AND " +
           "(:isRaw IS NULL OR p.isRaw = :isRaw) AND " +
           "(:startDate IS NULL OR p.dateTaken >= :startDate) AND " +
           "(:endDate IS NULL OR p.dateTaken <= :endDate)")
    List<Photo> searchPhotos(
        @Param("make") String make,
        @Param("model") String model,
        @Param("lens") String lens,
        @Param("isRaw") Boolean isRaw,
        @Param("startDate") LocalDateTime startDate,
        @Param("endDate") LocalDateTime endDate
    );
}
