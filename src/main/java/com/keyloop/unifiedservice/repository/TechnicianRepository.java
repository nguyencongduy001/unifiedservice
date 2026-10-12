package com.keyloop.unifiedservice.repository;

import com.keyloop.unifiedservice.entity.Technician;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.List;

public interface TechnicianRepository extends JpaRepository<Technician, Long> {

	List<Technician> findByDealershipId(Long dealershipId);

	/**
	 * Locks every technician of the dealership for the duration of the
	 * surrounding transaction so that two concurrent booking requests cannot
	 * assign the same technician.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select t from Technician t where t.dealership.id = :dealershipId order by t.id")
	List<Technician> findByDealershipIdLocking(@Param("dealershipId") Long dealershipId);
}
