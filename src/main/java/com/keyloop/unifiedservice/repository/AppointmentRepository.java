package com.keyloop.unifiedservice.repository;

import com.keyloop.unifiedservice.entity.Appointment;
import com.keyloop.unifiedservice.entity.AppointmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface AppointmentRepository extends JpaRepository<Appointment, Long> {

	/**
	 * Appointments at the given dealership whose time range overlaps
	 * {@code [startAt, endAt)} and that still consume resources
	 * (i.e. are not cancelled).
	 */
	@Query("""
			select a from Appointment a
			where a.dealership.id = :dealershipId
			  and a.status in :statuses
			  and a.startAt < :endAt
			  and a.endAt > :startAt
			""")
	List<Appointment> findOverlapping(@Param("dealershipId") Long dealershipId,
			@Param("statuses") Collection<AppointmentStatus> statuses,
			@Param("startAt") LocalDateTime startAt,
			@Param("endAt") LocalDateTime endAt);
}
