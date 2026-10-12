package com.keyloop.unifiedservice.repository;

import com.keyloop.unifiedservice.entity.ServiceBay;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.List;

public interface ServiceBayRepository extends JpaRepository<ServiceBay, Long> {

	List<ServiceBay> findByDealershipId(Long dealershipId);

	/**
	 * Locks every bay of the dealership for the duration of the surrounding
	 * transaction so that two concurrent booking requests cannot hand out the
	 * same bay.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select b from ServiceBay b where b.dealership.id = :dealershipId order by b.id")
	List<ServiceBay> findByDealershipIdLocking(@Param("dealershipId") Long dealershipId);
}
