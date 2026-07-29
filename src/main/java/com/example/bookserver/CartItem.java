package com.example.bookserver;

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
public class CartItem {

    private UUID userUuid;
    private UUID bookUuid;
    private Integer quantity;
    private LocalDateTime createdAt;
}
