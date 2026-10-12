package com.keyloop.unifiedservice.repository;

import com.keyloop.unifiedservice.entity.Customer;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerRepository extends JpaRepository<Customer, Long> {
}
