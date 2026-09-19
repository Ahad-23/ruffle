package com.changedetector.registry.repository;

import com.changedetector.registry.entity.SchemaChange;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SchemaChangeRepository extends JpaRepository<SchemaChange, Long> {
    List<SchemaChange> findByOldSpecVersionIdAndNewSpecVersionId(Long oldSpecVersionId, Long newSpecVersionId);
    List<SchemaChange> findByServiceId(Long serviceId);
}
