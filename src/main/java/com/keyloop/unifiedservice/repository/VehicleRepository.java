package com.keyloop.unifiedservice.repository;

import com.keyloop.unifiedservice.entity.Vehicle;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VehicleRepository extends JpaRepository<Vehicle, Long> {
}
