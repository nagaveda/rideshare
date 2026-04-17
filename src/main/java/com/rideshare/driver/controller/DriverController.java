package com.rideshare.driver.controller;

import com.rideshare.common.dto.ApiResponse;
import com.rideshare.driver.dto.DriverProfileResponse;
import com.rideshare.driver.dto.UpdateDriverProfileRequest;
import com.rideshare.driver.dto.UpdateDriverStatusRequest;
import com.rideshare.driver.service.DriverService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/drivers")
@RequiredArgsConstructor
@PreAuthorize("hasRole('DRIVER')")
public class DriverController {

    private final DriverService driverService;

    @GetMapping("/profile")
    public ResponseEntity<ApiResponse<DriverProfileResponse>> getProfile(@AuthenticationPrincipal UUID userId) {
        return ResponseEntity.ok(ApiResponse.ok(driverService.getProfile(userId)));
    }

    @PutMapping("/profile")
    public ResponseEntity<ApiResponse<DriverProfileResponse>> updateProfile(
            @AuthenticationPrincipal UUID userId,
            @Valid @RequestBody UpdateDriverProfileRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(driverService.updateProfile(userId, request)));
    }

    @PutMapping("/status")
    public ResponseEntity<ApiResponse<DriverProfileResponse>> updateStatus(
            @AuthenticationPrincipal UUID userId,
            @Valid @RequestBody UpdateDriverStatusRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(driverService.updateStatus(userId, request.getStatus())));
    }
}
