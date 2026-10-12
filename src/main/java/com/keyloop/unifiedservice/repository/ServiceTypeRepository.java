package com.keyloop.unifiedservice.repository;

import com.keyloop.unifiedservice.entity.ServiceType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ServiceTypeRepository extends JpaRepository<ServiceType, Long> {
}
