package com.keyloop.unifiedservice.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.HashSet;
import java.util.Set;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Technician {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private String name;

	@ManyToOne(optional = false, fetch = FetchType.LAZY)
	private Dealership dealership;

	/** The kinds of work this technician is qualified to perform. */
	@ManyToMany(fetch = FetchType.LAZY)
	@JoinTable(name = "technician_service_type",
			joinColumns = @JoinColumn(name = "technician_id"),
			inverseJoinColumns = @JoinColumn(name = "service_type_id"))
	private Set<ServiceType> qualifiedServiceTypes = new HashSet<>();
}
