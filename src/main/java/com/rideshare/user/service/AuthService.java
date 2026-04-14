package com.rideshare.user.service;

import com.rideshare.common.exception.BadRequestException;
import com.rideshare.common.exception.UnauthorizedException;
import com.rideshare.driver.model.DriverProfile;
import com.rideshare.driver.model.DriverStatus;
import com.rideshare.driver.repository.DriverProfileRepository;
import com.rideshare.user.dto.AuthResponse;
import com.rideshare.user.dto.LoginRequest;
import com.rideshare.user.dto.RegisterRequest;
import com.rideshare.user.model.Role;
import com.rideshare.user.model.User;
import com.rideshare.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final DriverProfileRepository driverProfileRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new BadRequestException("Email already registered");
        }

        User user = User.builder()
                .email(request.getEmail())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .phone(request.getPhone())
                .role(request.getRole())
                .build();

        user = userRepository.save(user);

        if (request.getRole() == Role.DRIVER) {
            DriverProfile profile = DriverProfile.builder()
                    .user(user)
                    .status(DriverStatus.OFFLINE)
                    .rating(java.math.BigDecimal.ZERO)
                    .totalRides(0)
                    .build();
            driverProfileRepository.save(profile);
        }

        String token = jwtService.generateToken(user.getId(), user.getEmail(), user.getRole());

        return AuthResponse.builder()
                .token(token)
                .userId(user.getId())
                .email(user.getEmail())
                .role(user.getRole())
                .build();
    }

    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new UnauthorizedException("Invalid email or password"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new UnauthorizedException("Invalid email or password");
        }

        String token = jwtService.generateToken(user.getId(), user.getEmail(), user.getRole());

        return AuthResponse.builder()
                .token(token)
                .userId(user.getId())
                .email(user.getEmail())
                .role(user.getRole())
                .build();
    }
}
