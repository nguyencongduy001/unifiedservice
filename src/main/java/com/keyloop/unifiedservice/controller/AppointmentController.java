package com.keyloop.unifiedservice.controller;

import com.keyloop.unifiedservice.dto.AppointmentResponse;
import com.keyloop.unifiedservice.dto.AvailabilityResponse;
import com.keyloop.unifiedservice.dto.CreateAppointmentRequest;
import com.keyloop.unifiedservice.service.AppointmentService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/api")
public class AppointmentController {

	private final AppointmentService appointmentService;

	public AppointmentController(AppointmentService appointmentService) {
		this.appointmentService = appointmentService;
	}

	/** Books a service appointment, subject to bay and technician availability. */
	@PostMapping("/appointments")
	public ResponseEntity<AppointmentResponse> create(@Valid @RequestBody CreateAppointmentRequest request) {
		AppointmentResponse created = appointmentService.create(request);
		return ResponseEntity.status(HttpStatus.CREATED).body(created);
	}

	@GetMapping("/appointments/{id}")
	public AppointmentResponse get(@PathVariable Long id) {
		return appointmentService.get(id);
	}

	/** Real-time availability of service bays and qualified technicians. */
	@GetMapping("/dealerships/{dealershipId}/availability")
	public AvailabilityResponse availability(@PathVariable Long dealershipId,
			@RequestParam Long serviceTypeId,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startAt) {
		return appointmentService.availability(dealershipId, serviceTypeId, startAt);
	}
}
