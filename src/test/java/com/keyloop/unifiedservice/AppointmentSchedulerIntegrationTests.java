package com.keyloop.unifiedservice;

import com.keyloop.unifiedservice.entity.AppointmentStatus;
import com.keyloop.unifiedservice.entity.Customer;
import com.keyloop.unifiedservice.entity.Dealership;
import com.keyloop.unifiedservice.entity.ServiceBay;
import com.keyloop.unifiedservice.entity.ServiceType;
import com.keyloop.unifiedservice.entity.Technician;
import com.keyloop.unifiedservice.entity.Vehicle;
import com.keyloop.unifiedservice.repository.AppointmentRepository;
import com.keyloop.unifiedservice.repository.CustomerRepository;
import com.keyloop.unifiedservice.repository.DealershipRepository;
import com.keyloop.unifiedservice.repository.ServiceBayRepository;
import com.keyloop.unifiedservice.repository.ServiceTypeRepository;
import com.keyloop.unifiedservice.repository.TechnicianRepository;
import com.keyloop.unifiedservice.repository.VehicleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage of the three core requirements: resource constrained
 * booking, real-time availability checking, and the confirmed appointment
 * record.
 */
@SpringBootTest(properties = {
		// Tests start against an empty database: create the schema namespace
		// and tables instead of relying on schema.sql (which production runs
		// once, where spring.jpa.hibernate.ddl-auto=update then finds them).
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"spring.jpa.properties.hibernate.hbm2ddl.create_namespaces=true"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AppointmentSchedulerIntegrationTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private CustomerRepository customerRepository;

	@Autowired
	private DealershipRepository dealershipRepository;

	@Autowired
	private VehicleRepository vehicleRepository;

	@Autowired
	private ServiceTypeRepository serviceTypeRepository;

	@Autowired
	private ServiceBayRepository serviceBayRepository;

	@Autowired
	private TechnicianRepository technicianRepository;

	@Autowired
	private AppointmentRepository appointmentRepository;

	private Dealership dealership;
	private ServiceType oilChange;
	private ServiceBay serviceBay;
	private Technician technician;
	private Customer customer;
	private Vehicle vehicle;

	@BeforeEach
	void setUp() {
		dealership = saveDealership("Dealership " + shortId());

		oilChange = new ServiceType();
		oilChange.setName("Oil Change");
		oilChange.setDurationMinutes(30);
		oilChange = serviceTypeRepository.save(oilChange);

		serviceBay = new ServiceBay();
		serviceBay.setName("Bay " + shortId());
		serviceBay.setDealership(dealership);
		serviceBay = serviceBayRepository.save(serviceBay);

		technician = new Technician();
		technician.setName("Technician " + shortId());
		technician.setDealership(dealership);
		technician.setQualifiedServiceTypes(Set.of(oilChange));
		technician = technicianRepository.save(technician);

		customer = new Customer();
		customer.setName("Jane Doe " + shortId());
		customer.setEmail("jane." + shortId() + "@example.com");
		customer = customerRepository.save(customer);

		vehicle = new Vehicle();
		vehicle.setRegistration("KA-" + shortId());
		vehicle.setMake("Volkswagen");
		vehicle.setModel("Golf");
		vehicle.setOwner(customer);
		vehicle = vehicleRepository.save(vehicle);
	}

	@Test
	void createsConfirmedAppointmentWhenBayAndTechnicianAreFree() throws Exception {
		LocalDateTime startAt = futureSlot();

		mockMvc.perform(post("/api/appointments")
					.contentType(MediaType.APPLICATION_JSON)
					.content(requestJson(vehicle.getId(), oilChange.getId(), dealership.getId(), startAt)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value(AppointmentStatus.CONFIRMED.name()))
				.andExpect(jsonPath("$.customerId").value(customer.getId()))
				.andExpect(jsonPath("$.customerName").value(customer.getName()))
				.andExpect(jsonPath("$.vehicleId").value(vehicle.getId()))
				.andExpect(jsonPath("$.serviceTypeId").value(oilChange.getId()))
				.andExpect(jsonPath("$.dealershipId").value(dealership.getId()))
				.andExpect(jsonPath("$.serviceBayId").value(serviceBay.getId()))
				.andExpect(jsonPath("$.technicianId").value(technician.getId()))
				.andExpect(jsonPath("$.startAt").value(iso(startAt)))
				.andExpect(jsonPath("$.endAt").value(iso(startAt.plusMinutes(30))));

		// Requirement 3: a persistent record linking customer, vehicle,
		// technician and service bay.
		List<?> persisted = appointmentRepository.findOverlapping(
				dealership.getId(), List.of(AppointmentStatus.CONFIRMED), startAt, startAt.plusMinutes(30));
		assertThat(persisted).hasSize(1);
	}

	@Test
	void rejectsOverlappingBookingWhenBayAndTechnicianAreBusy() throws Exception {
		LocalDateTime startAt = futureSlot();
		bookSuccessfully(startAt);
		long bookingsBefore = appointmentRepository.count();

		// Overlaps [09:00, 09:30) so both the bay and the technician are taken.
		mockMvc.perform(post("/api/appointments")
					.contentType(MediaType.APPLICATION_JSON)
					.content(requestJson(vehicle.getId(), oilChange.getId(), dealership.getId(), startAt.plusMinutes(15))))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("available")));

		assertThat(appointmentRepository.count()).isEqualTo(bookingsBefore);
	}

	@Test
	void acceptsBookingThatStartsAfterThePreviousOneEnds() throws Exception {
		LocalDateTime startAt = futureSlot();
		bookSuccessfully(startAt);

		mockMvc.perform(post("/api/appointments")
					.contentType(MediaType.APPLICATION_JSON)
					.content(requestJson(vehicle.getId(), oilChange.getId(), dealership.getId(), startAt.plusMinutes(30))))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value(AppointmentStatus.CONFIRMED.name()));
	}

	@Test
	void rejectsBookingWhenNoTechnicianIsQualifiedForTheService() throws Exception {
		Dealership other = saveDealership("Dealership " + shortId());

		ServiceBay otherBay = new ServiceBay();
		otherBay.setName("Bay " + shortId());
		otherBay.setDealership(other);
		serviceBayRepository.save(otherBay);

		ServiceType fullService = new ServiceType();
		fullService.setName("Full Service");
		fullService.setDurationMinutes(120);
		fullService = serviceTypeRepository.save(fullService);

		Technician unqualified = new Technician();
		unqualified.setName("Technician " + shortId());
		unqualified.setDealership(other);
		unqualified.setQualifiedServiceTypes(Set.of(fullService)); // not oil change
		technicianRepository.save(unqualified);

		LocalDateTime startAt = futureSlot();
		mockMvc.perform(post("/api/appointments")
					.contentType(MediaType.APPLICATION_JSON)
					.content(requestJson(vehicle.getId(), oilChange.getId(), other.getId(), startAt)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message")
						.value(org.hamcrest.Matchers.containsString("qualified technician")));
	}

	@Test
	void returnsNotFoundForUnknownVehicle() throws Exception {
		mockMvc.perform(post("/api/appointments")
					.contentType(MediaType.APPLICATION_JSON)
					.content(requestJson(999_999L, oilChange.getId(), dealership.getId(), futureSlot())))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error").value("Not Found"));
	}

	@Test
	void returnsValidationErrorsForIncompleteRequest() throws Exception {
		mockMvc.perform(post("/api/appointments")
					.contentType(MediaType.APPLICATION_JSON)
					.content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.fieldErrors.vehicleId").exists())
				.andExpect(jsonPath("$.fieldErrors.desiredStart").exists());
	}

	@Test
	void availabilityEndpointReflectsBookedResources() throws Exception {
		LocalDateTime startAt = futureSlot();

		mockMvc.perform(get("/api/dealerships/{dealershipId}/availability", dealership.getId())
					.param("serviceTypeId", String.valueOf(oilChange.getId()))
					.param("startAt", startAt.toString()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.availableServiceBays[0].id").value(serviceBay.getId()))
				.andExpect(jsonPath("$.availableTechnicians[0].id").value(technician.getId()));

		bookSuccessfully(startAt);

		mockMvc.perform(get("/api/dealerships/{dealershipId}/availability", dealership.getId())
					.param("serviceTypeId", String.valueOf(oilChange.getId()))
					.param("startAt", startAt.toString()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.availableServiceBays").isEmpty())
				.andExpect(jsonPath("$.availableTechnicians").isEmpty());
	}

	@Test
	void onlyOneOfTwoConcurrentBookingsForTheSameSlotSucceeds() throws Exception {
		LocalDateTime startAt = futureSlot();
		long bookingsBefore = appointmentRepository.count();

		int attempts = 2;
		CyclicBarrier startTogether = new CyclicBarrier(attempts);
		ExecutorService pool = Executors.newFixedThreadPool(attempts);
		try {
			List<Future<Integer>> statuses = new ArrayList<>();
			for (int i = 0; i < attempts; i++) {
				statuses.add(pool.submit(() -> {
					startTogether.await(10, TimeUnit.SECONDS);
					return mockMvc.perform(post("/api/appointments")
									.contentType(MediaType.APPLICATION_JSON)
									.content(requestJson(vehicle.getId(), oilChange.getId(),
											dealership.getId(), startAt)))
							.andReturn()
							.getResponse()
							.getStatus();
				}));
			}
			List<Integer> results = new ArrayList<>();
			for (Future<Integer> status : statuses) {
				results.add(status.get(30, TimeUnit.SECONDS));
			}
			// Exactly one wins the slot: the other is told it is unavailable.
			assertThat(results).containsExactlyInAnyOrder(201, 409);
			assertThat(appointmentRepository.count()).isEqualTo(bookingsBefore + 1);
		} finally {
			pool.shutdownNow();
		}
	}

	private void bookSuccessfully(LocalDateTime startAt) throws Exception {
		mockMvc.perform(post("/api/appointments")
					.contentType(MediaType.APPLICATION_JSON)
					.content(requestJson(vehicle.getId(), oilChange.getId(), dealership.getId(), startAt)))
				.andExpect(status().isCreated());
	}

	private String requestJson(Long vehicleId, Long serviceTypeId, Long dealershipId, LocalDateTime startAt) {
		return """
				{"vehicleId":%d,"serviceTypeId":%d,"dealershipId":%d,"desiredStart":"%s"}
				""".formatted(vehicleId, serviceTypeId, dealershipId, startAt);
	}

	private Dealership saveDealership(String name) {
		Dealership entity = new Dealership();
		entity.setName(name);
		return dealershipRepository.save(entity);
	}

	private LocalDateTime futureSlot() {
		return LocalDateTime.now().plusDays(2).withHour(9).withMinute(0).withSecond(0).withNano(0);
	}

	/** The exact form Jackson writes java.time values in (seconds always shown). */
	private String iso(LocalDateTime time) {
		return time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
	}

	private String shortId() {
		return UUID.randomUUID().toString().substring(0, 8);
	}
}
