package com.rideshare.driver.dto;

import com.rideshare.driver.model.DriverStatus;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateDriverStatusRequest {

    @NotNull(message = "Status is required")
    private DriverStatus status;
}
