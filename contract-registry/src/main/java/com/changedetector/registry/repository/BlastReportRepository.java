package com.changedetector.registry.repository;

import com.changedetector.registry.entity.BlastReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BlastReportRepository extends JpaRepository<BlastReport, Long> {
    List<BlastReport> findByServiceIdOrderByGeneratedAtDesc(Long serviceId);
    Optional<BlastReport> findByOldSpecVersionIdAndNewSpecVersionId(Long oldSpecVersionId, Long newSpecVersionId);
}
