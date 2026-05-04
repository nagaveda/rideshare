package com.rideshare.payment.controller;

import com.rideshare.common.dto.ApiResponse;
import com.rideshare.payment.dto.PaymentResponse;
import com.rideshare.payment.model.Payment;
import com.rideshare.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @GetMapping("/history")
    public ResponseEntity<ApiResponse<Page<PaymentResponse>>> getHistory(
            Authentication authentication,
            @AuthenticationPrincipal UUID userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size);
        boolean isDriver = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_DRIVER"));
        Page<Payment> payments = isDriver
                ? paymentService.getDriverHistory(userId, pageable)
                : paymentService.getRiderHistory(userId, pageable);
        return ResponseEntity.ok(ApiResponse.ok(payments.map(PaymentResponse::from)));
    }
}
