package com.example.bookserver.user;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class User {
    private UUID userUuid;
    private String userId;
    private String userPassword;
    private String userName;
    private String phone;
    private LocalDate birthDate;
    private LocalDateTime createdAt;
}
