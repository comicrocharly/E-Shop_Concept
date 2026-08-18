package com.eshop.service;

import com.eshop.dto.AddAddressRequest;
import com.eshop.dto.AddressResponse;
import com.eshop.entity.Address;
import com.eshop.entity.User;
import com.eshop.repository.AddressRepository;
import com.eshop.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AddressService {

    private final AddressRepository addressRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<AddressResponse> findByUserId(Long userId) {
        return addressRepository.findByUserId(userId)
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public AddressResponse add(Long userId, AddAddressRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User not found: " + userId));

        Address address = Address.builder()
                .street(request.getStreet())
                .streetNumber(request.getStreetNumber())
                .postalCode(request.getPostalCode())
                .city(request.getCity())
                .country(request.getCountry())
                .user(user)
                .build();

        user.getAddresses().add(address);
        addressRepository.save(address);
        return toResponse(address);
    }

    @Transactional
    public void delete(Long userId, Long addressId) {
        addressRepository.findById(addressId)
                .filter(addr -> addr.getUser().getId().equals(userId))
                .ifPresentOrElse(
                        addressRepository::delete,
                        () -> { throw new EntityNotFoundException("Address not found or not owned by user"); }
                );
    }

    private AddressResponse toResponse(Address address) {
        return AddressResponse.builder()
                .id(address.getId())
                .street(address.getStreet())
                .streetNumber(address.getStreetNumber())
                .postalCode(address.getPostalCode())
                .city(address.getCity())
                .country(address.getCountry())
                .build();
    }
}
