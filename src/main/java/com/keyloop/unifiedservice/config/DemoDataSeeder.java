package com.keyloop.unifiedservice.config;

import com.keyloop.unifiedservice.entity.Customer;
import com.keyloop.unifiedservice.entity.Dealership;
import com.keyloop.unifiedservice.entity.ServiceBay;
import com.keyloop.unifiedservice.entity.ServiceType;
import com.keyloop.unifiedservice.entity.Technician;
import com.keyloop.unifiedservice.entity.Vehicle;
import com.keyloop.unifiedservice.repository.CustomerRepository;
import com.keyloop.unifiedservice.repository.DealershipRepository;
import com.keyloop.unifiedservice.repository.ServiceBayRepository;
import com.keyloop.unifiedservice.repository.ServiceTypeRepository;
import com.keyloop.unifiedservice.repository.TechnicianRepository;
import com.keyloop.unifiedservice.repository.VehicleRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Loads a minimal, realistic dataset so the scheduler can be demonstrated
 * immediately after startup. Idempotent: it exits as soon as any data exists,
 * so it never overwrites real records.
 */
@Component
public class DemoDataSeeder implements CommandLineRunner {

	/** Set {@code app.seed-demo-data=false} to keep the database untouched. */
	private final boolean enabled;

	private final CustomerRepository customerRepository;
	private final DealershipRepository dealershipRepository;
	private final VehicleRepository vehicleRepository;
	private final ServiceTypeRepository serviceTypeRepository;
	private final ServiceBayRepository serviceBayRepository;
	private final TechnicianRepository technicianRepository;

	public DemoDataSeeder(@Value("${app.seed-demo-data:true}") boolean enabled,
			CustomerRepository customerRepository,
			DealershipRepository dealershipRepository,
			VehicleRepository vehicleRepository,
			ServiceTypeRepository serviceTypeRepository,
			ServiceBayRepository serviceBayRepository,
			TechnicianRepository technicianRepository) {
		this.enabled = enabled;
		this.customerRepository = customerRepository;
		this.dealershipRepository = dealershipRepository;
		this.vehicleRepository = vehicleRepository;
		this.serviceTypeRepository = serviceTypeRepository;
		this.serviceBayRepository = serviceBayRepository;
		this.technicianRepository = technicianRepository;
	}

	@Override
	public void run(String... args) {
		if (!enabled || dealershipRepository.count() > 0 || customerRepository.count() > 0) {
			return;
		}

		Dealership dealership = new Dealership();
		dealership.setName("Keyloop Demo Dealership");
		dealership = dealershipRepository.save(dealership);

		ServiceType oilChange = saveServiceType("Oil Change", 30);
		ServiceType tyreRotation = saveServiceType("Tyre Rotation", 45);
		ServiceType fullService = saveServiceType("Full Service", 120);

		saveServiceBay("Bay 1", dealership);
		saveServiceBay("Bay 2", dealership);

		saveTechnician("Alice Ahmed", dealership, Set.of(oilChange, tyreRotation));
		saveTechnician("Bob Barnes", dealership, Set.of(tyreRotation, fullService));

		Customer customer = new Customer();
		customer.setName("Jane Doe");
		customer.setEmail("jane.doe@example.com");
		customer = customerRepository.save(customer);

		Vehicle vehicle = new Vehicle();
		vehicle.setRegistration("KL-01-DEMO");
		vehicle.setMake("Volkswagen");
		vehicle.setModel("Golf");
		vehicle.setOwner(customer);
		vehicleRepository.save(vehicle);
	}

	private ServiceType saveServiceType(String name, int durationMinutes) {
		ServiceType serviceType = new ServiceType();
		serviceType.setName(name);
		serviceType.setDurationMinutes(durationMinutes);
		return serviceTypeRepository.save(serviceType);
	}

	private void saveServiceBay(String name, Dealership dealership) {
		ServiceBay serviceBay = new ServiceBay();
		serviceBay.setName(name);
		serviceBay.setDealership(dealership);
		serviceBayRepository.save(serviceBay);
	}

	private void saveTechnician(String name, Dealership dealership, Set<ServiceType> qualifiedServiceTypes) {
		Technician technician = new Technician();
		technician.setName(name);
		technician.setDealership(dealership);
		technician.setQualifiedServiceTypes(qualifiedServiceTypes);
		technicianRepository.save(technician);
	}
}
