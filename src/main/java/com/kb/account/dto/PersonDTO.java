package com.kb.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PersonDTO {
    private Long id;

    @NotBlank(message = "Name is required")
    private String name;

    private String phone;

    @NotNull(message = "User ID is required")
    private Long userId;
}
