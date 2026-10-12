package com.keyloop.unifiedservice.dto;

import java.time.LocalDateTime;

public record AppointmentResponse(

		Long id,

		String status,

		Long customerId,
		String customerName,

		Long vehicleId,
		String vehicleRegistration,

		Long serviceTypeId,
		String serviceTypeName,

		Long dealershipId,
		String dealershipName,

		Long serviceBayId,
		String serviceBayName,

		Long technicianId,
		String technicianName,

		LocalDateTime startAt,
		LocalDateTime endAt) {
}
