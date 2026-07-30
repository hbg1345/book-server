package com.example.bookserver.user;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.bookserver.user.dto.ChangePasswordRequest;
import com.example.bookserver.user.dto.RegisterRequest;
import com.example.bookserver.user.dto.RegisterResponse;
import com.example.bookserver.user.dto.UpdateProfileRequest;
import com.example.bookserver.user.dto.UserProfileResponse;

import jakarta.validation.Valid;

/**
 * User endpoints. Registration is public; the {@code /me} endpoints operate on the
 * authenticated user, whose uuid is carried by the JWT and injected via
 * {@link AuthenticationPrincipal}. Login/logout live in {@code AuthController}.
 */
@RestController
@RequestMapping("/api")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    public RegisterResponse register(@Valid @RequestBody RegisterRequest req) {
        UUID userUuid = userService.register(
                req.userId(), req.password(), req.userName(), req.phone(), req.birthDate());
        return new RegisterResponse(userUuid);
    }

    @GetMapping("/users/me")
    public UserProfileResponse getMyProfile(@AuthenticationPrincipal UUID userUuid) {
        return UserProfileResponse.from(userService.getProfile(userUuid));
    }

    @PutMapping("/users/me")
    public void updateProfile(@AuthenticationPrincipal UUID userUuid,
                              @Valid @RequestBody UpdateProfileRequest req) {
        userService.updateProfile(userUuid, req.userName(), req.phone(), req.birthDate());
    }

    @PutMapping("/users/me/password")
    public void changePassword(@AuthenticationPrincipal UUID userUuid,
                               @Valid @RequestBody ChangePasswordRequest req) {
        userService.changePassword(userUuid, req.currentPassword(), req.newPassword());
    }

    @DeleteMapping("/users/me")
    public void withdraw(@AuthenticationPrincipal UUID userUuid) {
        userService.withdraw(userUuid);   // refresh tokens cascade-delete via the FK
    }
}
