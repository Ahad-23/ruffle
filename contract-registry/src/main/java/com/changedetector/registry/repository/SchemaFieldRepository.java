package com.changedetector.registry.repository;

import com.changedetector.registry.entity.SchemaField;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SchemaFieldRepository extends JpaRepository<SchemaField, Long> {
    List<SchemaField> findByEndpointId(Long endpointId);

    @Query("SELECT f FROM SchemaField f WHERE f.endpoint.specVersion.id = :specVersionId")
    List<SchemaField> findBySpecVersionId(@Param("specVersionId") Long specVersionId);

    @Query("SELECT f FROM SchemaField f WHERE f.endpoint.specVersion.service.id = :serviceId")
    List<SchemaField> findByServiceId(@Param("serviceId") Long serviceId);
}
