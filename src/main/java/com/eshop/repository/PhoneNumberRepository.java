package com.eshop.repository;

import com.eshop.entity.PhoneNumber;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PhoneNumberRepository extends JpaRepository<PhoneNumber, Long> {
    List<PhoneNumber> findByUserId(Long userId);
}
