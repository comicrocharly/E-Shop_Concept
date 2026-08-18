package com.eshop.service;

import com.eshop.dto.AddPhoneNumberRequest;
import com.eshop.dto.PhoneNumberResponse;
import com.eshop.entity.PhoneNumber;
import com.eshop.entity.User;
import com.eshop.repository.PhoneNumberRepository;
import com.eshop.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PhoneNumberService {

    private final PhoneNumberRepository phoneRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<PhoneNumberResponse> findByUserId(Long userId) {
        return phoneRepository.findByUserId(userId)
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public PhoneNumberResponse add(Long userId, AddPhoneNumberRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User not found: " + userId));

        PhoneNumber phone = PhoneNumber.builder()
                .countryPrefix(request.getCountryPrefix())
                .number(request.getNumber())
                .phoneType(PhoneNumber.PhoneType.valueOf(request.getPhoneType()))
                .user(user)
                .build();

        user.getPhoneNumbers().add(phone);
        phoneRepository.save(phone);
        return toResponse(phone);
    }

    @Transactional
    public void delete(Long userId, Long phoneId) {
        phoneRepository.findById(phoneId)
                .filter(phone -> phone.getUser().getId().equals(userId))
                .ifPresentOrElse(
                        phoneRepository::delete,
                        () -> { throw new EntityNotFoundException("Phone number not found or not owned by user"); }
                );
    }

    private PhoneNumberResponse toResponse(PhoneNumber phone) {
        return PhoneNumberResponse.builder()
                .id(phone.getId())
                .countryPrefix(phone.getCountryPrefix())
                .number(phone.getNumber())
                .phoneType(phone.getPhoneType().name())
                .build();
    }
}
