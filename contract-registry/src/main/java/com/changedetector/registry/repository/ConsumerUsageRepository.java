package com.changedetector.registry.repository;

import com.changedetector.registry.entity.ConsumerUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ConsumerUsageRepository extends JpaRepository<ConsumerUsage, Long> {

    List<ConsumerUsage> findByProviderService(String providerService);

    List<ConsumerUsage> findByConsumerService(String consumerService);

    List<ConsumerUsage> findByScanId(String scanId);

    @Modifying
    @Query("DELETE FROM ConsumerUsage cu WHERE cu.consumerService = :consumerService")
    void deleteByConsumerService(@Param("consumerService") String consumerService);

    @Query("SELECT cu FROM ConsumerUsage cu WHERE cu.providerService = :providerService AND cu.endpointPath = :endpointPath")
    List<ConsumerUsage> findByProviderServiceAndEndpointPath(@Param("providerService") String providerService,
                                                            @Param("endpointPath") String endpointPath);

    @Query("SELECT DISTINCT cu.consumerService FROM ConsumerUsage cu WHERE cu.providerService = :providerService")
    List<String> findDistinctConsumerServicesForProvider(@Param("providerService") String providerService);

    @Query("SELECT DISTINCT cu.consumerService FROM ConsumerUsage cu")
    List<String> findAllDistinctConsumers();

    @Query("SELECT DISTINCT cu.providerService FROM ConsumerUsage cu")
    List<String> findAllDistinctProviders();
}
