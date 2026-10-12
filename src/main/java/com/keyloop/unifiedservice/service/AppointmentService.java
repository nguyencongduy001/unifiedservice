package com.keyloop.unifiedservice.service;

import com.keyloop.unifiedservice.dto.AppointmentResponse;
import com.keyloop.unifiedservice.dto.AvailabilityResponse;
import com.keyloop.unifiedservice.dto.AvailabilityResponse.ResourceRef;
import com.keyloop.unifiedservice.dto.CreateAppointmentRequest;
import com.keyloop.unifiedservice.entity.Appointment;
import com.keyloop.unifiedservice.entity.AppointmentStatus;
import com.keyloop.unifiedservice.entity.Dealership;
import com.keyloop.unifiedservice.entity.ServiceBay;
import com.keyloop.unifiedservice.entity.ServiceType;
import com.keyloop.unifiedservice.entity.Technician;
import com.keyloop.unifiedservice.entity.Vehicle;
import com.keyloop.unifiedservice.exception.NoResourceAvailableException;
import com.keyloop.unifiedservice.exception.ResourceNotFoundException;
import com.keyloop.unifiedservice.repository.AppointmentRepository;
import com.keyloop.unifiedservice.repository.DealershipRepository;
import com.keyloop.unifiedservice.repository.ServiceBayRepository;
import com.keyloop.unifiedservice.repository.ServiceTypeRepository;
import com.keyloop.unifiedservice.repository.TechnicianRepository;
import com.keyloop.unifiedservice.repository.VehicleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Books service appointments subject to resource constraints: a confirmed
 * appointment must hold a service bay and a qualified technician for the whole
 * duration of the service.
 */
@Service
public class AppointmentService {

	/** Only these statuses hold resources for their time window. */
	private static final Set<AppointmentStatus> ACTIVE_STATUSES = Set.of(AppointmentStatus.CONFIRMED);

	private final VehicleRepository vehicleRepository;
	private final ServiceTypeRepository serviceTypeRepository;
	private final DealershipRepository dealershipRepository;
	private final ServiceBayRepository serviceBayRepository;
	private final TechnicianRepository technicianRepository;
	private final AppointmentRepository appointmentRepository;

	public AppointmentService(VehicleRepository vehicleRepository,
			ServiceTypeRepository serviceTypeRepository,
			DealershipRepository dealershipRepository,
			ServiceBayRepository serviceBayRepository,
			TechnicianRepository technicianRepository,
			AppointmentRepository appointmentRepository) {
		this.vehicleRepository = vehicleRepository;
		this.serviceTypeRepository = serviceTypeRepository;
		this.dealershipRepository = dealershipRepository;
		this.serviceBayRepository = serviceBayRepository;
		this.technicianRepository = technicianRepository;
		this.appointmentRepository = appointmentRepository;
	}

	/**
	 * Requirement 1 &amp; 3: accept the booking request and, only if a bay and a
	 * qualified technician are both free for the whole service duration,
	 * persist a confirmed appointment linking customer, vehicle, technician and
	 * service bay.
	 */
	@Transactional
	public AppointmentResponse create(CreateAppointmentRequest request) {
		Vehicle vehicle = vehicleRepository.findById(request.vehicleId())
				.orElseThrow(() -> new ResourceNotFoundException(
						"Vehicle " + request.vehicleId() + " not found"));
		ServiceType serviceType = serviceType(request.serviceTypeId());
		Dealership dealership = dealership(request.dealershipId());

		LocalDateTime startAt = request.desiredStart();
		LocalDateTime endAt = startAt.plusMinutes(serviceType.getDurationMinutes());

		// Requirement 2: real-time check that a bay AND a qualified technician
		// are free for the entire duration. Resources are locked so a
		// concurrent request cannot double-book them.
		Availability availability = computeAvailability(dealership, serviceType, startAt, endAt, true);
		if (availability.serviceBays().isEmpty() || availability.technicians().isEmpty()) {
			throw new NoResourceAvailableException(unavailableMessage(dealership, startAt, endAt, availability));
		}

		Appointment appointment = new Appointment();
		appointment.setCustomer(vehicle.getOwner());
		appointment.setVehicle(vehicle);
		appointment.setServiceType(serviceType);
		appointment.setDealership(dealership);
		appointment.setServiceBay(availability.serviceBays().get(0));
		appointment.setTechnician(availability.technicians().get(0));
		appointment.setStartAt(startAt);
		appointment.setEndAt(endAt);
		appointment.setStatus(AppointmentStatus.CONFIRMED);

		return toResponse(appointmentRepository.save(appointment));
	}

	/** Real-time view of what is still bookable at the dealership. */
	@Transactional(readOnly = true)
	public AvailabilityResponse availability(Long dealershipId, Long serviceTypeId, LocalDateTime startAt) {
		ServiceType serviceType = serviceType(serviceTypeId);
		Dealership dealership = dealership(dealershipId);
		LocalDateTime endAt = startAt.plusMinutes(serviceType.getDurationMinutes());

		Availability availability = computeAvailability(dealership, serviceType, startAt, endAt, false);
		return new AvailabilityResponse(
				dealership.getId(),
				dealership.getName(),
				serviceType.getId(),
				serviceType.getDurationMinutes(),
				startAt,
				endAt,
				availability.serviceBays().stream().map(bay -> new ResourceRef(bay.getId(), bay.getName())).toList(),
				availability.technicians().stream().map(tech -> new ResourceRef(tech.getId(), tech.getName())).toList());
	}

	@Transactional(readOnly = true)
	public AppointmentResponse get(Long id) {
		return appointmentRepository.findById(id)
				.map(this::toResponse)
				.orElseThrow(() -> new ResourceNotFoundException("Appointment " + id + " not found"));
	}

	/**
	 * Requirement 2: the bays and technicians of the dealership that have no
	 * appointment overlapping {@code [startAt, endAt)}. Bays and technicians are
	 * read in a deterministic order so that the chosen assignment is stable, and
	 * are pessimistically locked when called from a booking so that the check
	 * and the assignment happen atomically.
	 */
	private Availability computeAvailability(Dealership dealership, ServiceType serviceType,
			LocalDateTime startAt, LocalDateTime endAt, boolean lockResources) {

		List<ServiceBay> candidateBays = lockResources
				? serviceBayRepository.findByDealershipIdLocking(dealership.getId())
				: serviceBayRepository.findByDealershipId(dealership.getId());
		List<Technician> candidateTechnicians = lockResources
				? technicianRepository.findByDealershipIdLocking(dealership.getId())
				: technicianRepository.findByDealershipId(dealership.getId());

		Set<Long> busyServiceBayIds = new HashSet<>();
		Set<Long> busyTechnicianIds = new HashSet<>();
		List<Appointment> clashes = appointmentRepository.findOverlapping(
				dealership.getId(), ACTIVE_STATUSES, startAt, endAt);
		for (Appointment clash : clashes) {
			busyServiceBayIds.add(clash.getServiceBay().getId());
			busyTechnicianIds.add(clash.getTechnician().getId());
		}

		List<ServiceBay> freeBays = candidateBays.stream()
				.filter(bay -> !busyServiceBayIds.contains(bay.getId()))
				.toList();
		List<Technician> freeTechnicians = candidateTechnicians.stream()
				.filter(technician -> isQualifiedFor(technician, serviceType))
				.filter(technician -> !busyTechnicianIds.contains(technician.getId()))
				.toList();

		return new Availability(freeBays, freeTechnicians);
	}

	private boolean isQualifiedFor(Technician technician, ServiceType serviceType) {
		return technician.getQualifiedServiceTypes().stream()
				.anyMatch(qualified -> qualified.getId().equals(serviceType.getId()));
	}

	private String unavailableMessage(Dealership dealership, LocalDateTime startAt, LocalDateTime endAt,
			Availability availability) {
		List<String> missing = new ArrayList<>();
		if (availability.serviceBays().isEmpty()) {
			missing.add("service bay");
		}
		if (availability.technicians().isEmpty()) {
			missing.add("qualified technician");
		}
		return "No " + String.join(" and no ", missing)
				+ " available at dealership '" + dealership.getName()
				+ "' between " + startAt + " and " + endAt;
	}

	private AppointmentResponse toResponse(Appointment appointment) {
		return new AppointmentResponse(
				appointment.getId(),
				appointment.getStatus().name(),
				appointment.getCustomer().getId(),
				appointment.getCustomer().getName(),
				appointment.getVehicle().getId(),
				appointment.getVehicle().getRegistration(),
				appointment.getServiceType().getId(),
				appointment.getServiceType().getName(),
				appointment.getDealership().getId(),
				appointment.getDealership().getName(),
				appointment.getServiceBay().getId(),
				appointment.getServiceBay().getName(),
				appointment.getTechnician().getId(),
				appointment.getTechnician().getName(),
				appointment.getStartAt(),
				appointment.getEndAt());
	}

	private ServiceType serviceType(Long id) {
		return serviceTypeRepository.findById(id)
				.orElseThrow(() -> new ResourceNotFoundException("Service type " + id + " not found"));
	}

	private Dealership dealership(Long id) {
		return dealershipRepository.findById(id)
				.orElseThrow(() -> new ResourceNotFoundException("Dealership " + id + " not found"));
	}

	private record Availability(List<ServiceBay> serviceBays, List<Technician> technicians) {
	}
}
