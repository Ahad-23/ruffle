package com.changedetector.registry.repository;

import com.changedetector.registry.entity.Endpoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EndpointRepository extends JpaRepository<Endpoint, Long> {
    List<Endpoint> findBySpecVersionId(Long specVersionId);
    Optional<Endpoint> findBySpecVersionIdAndHttpMethodAndPath(Long specVersionId, String httpMethod, String path);
}
