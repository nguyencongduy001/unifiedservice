package com.keyloop.unifiedservice.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Appointment {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(optional = false, fetch = FetchType.LAZY)
	private Customer customer;

	@ManyToOne(optional = false, fetch = FetchType.LAZY)
	private Vehicle vehicle;

	@ManyToOne(optional = false, fetch = FetchType.LAZY)
	private ServiceType serviceType;

	@ManyToOne(optional = false, fetch = FetchType.LAZY)
	private Dealership dealership;

	@ManyToOne(optional = false, fetch = FetchType.LAZY)
	private ServiceBay serviceBay;

	@ManyToOne(optional = false, fetch = FetchType.LAZY)
	private Technician technician;

	private LocalDateTime startAt;

	private LocalDateTime endAt;

	@Enumerated(EnumType.STRING)
	private AppointmentStatus status;
}
