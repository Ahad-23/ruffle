package com.changedetector.registry.repository;

import com.changedetector.registry.entity.SpecVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SpecVersionRepository extends JpaRepository<SpecVersion, Long> {
    List<SpecVersion> findByServiceIdOrderByIngestedAtDesc(Long serviceId);
    Optional<SpecVersion> findByServiceIdAndSpecHash(Long serviceId, String specHash);
    
    @Query("SELECT sv FROM SpecVersion sv WHERE sv.service.id = :serviceId ORDER BY sv.ingestedAt DESC LIMIT 1")
    Optional<SpecVersion> findLatestByServiceId(@Param("serviceId") Long serviceId);
}
