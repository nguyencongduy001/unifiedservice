package com.keyloop.unifiedservice.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Future;

import java.time.LocalDateTime;

/**
 * A request to book a service appointment. The customer is implied by the
 * vehicle's owner; the service bay and technician are assigned by the service
 * once availability has been confirmed.
 */
public record CreateAppointmentRequest(

		@NotNull(message = "vehicleId is required")
		Long vehicleId,

		@NotNull(message = "serviceTypeId is required")
		Long serviceTypeId,

		@NotNull(message = "dealershipId is required")
		Long dealershipId,

		@NotNull(message = "desiredStart is required")
		@Future(message = "desiredStart must be in the future")
		LocalDateTime desiredStart) {
}
