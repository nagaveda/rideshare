package com.rideshare.user.dto;

import com.rideshare.user.model.Role;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserProfileResponse {

    private UUID userId;
    private String email;
    private String firstName;
    private String lastName;
    private String phone;
    private Role role;
}
