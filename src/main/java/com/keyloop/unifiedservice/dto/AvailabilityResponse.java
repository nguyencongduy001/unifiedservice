package com.keyloop.unifiedservice.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Snapshot of the resources that are still free for the requested service at
 * the requested time.
 */
public record AvailabilityResponse(

		Long dealershipId,
		String dealershipName,

		Long serviceTypeId,
		int serviceDurationMinutes,

		LocalDateTime startAt,
		LocalDateTime endAt,

		List<ResourceRef> availableServiceBays,
		List<ResourceRef> availableTechnicians) {

	public record ResourceRef(Long id, String name) {
	}
}
