package com.example.bookserver.user;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.bookserver.common.NotLoggedInException;
import com.example.bookserver.user.dto.ChangePasswordRequest;
import com.example.bookserver.user.dto.LoginRequest;
import com.example.bookserver.user.dto.RegisterRequest;
import com.example.bookserver.user.dto.RegisterResponse;
import com.example.bookserver.user.dto.UpdateProfileRequest;
import com.example.bookserver.user.dto.UserProfileResponse;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;

/**
 * User endpoints. Identity is carried by the HTTP session: {@code login} stores the
 * user_uuid, the {@code /me} endpoints read it back. (Placeholder until Spring Security.)
 */
@RestController
@RequestMapping("/api")
public class UserController {

    /** Session attribute holding the logged-in user's uuid. */
    static final String SESSION_USER = "userUuid";

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

    @PostMapping("/auth/login")
    public void login(@Valid @RequestBody LoginRequest req, HttpSession session) {
        UUID userUuid = userService.login(req.userId(), req.password());
        session.setAttribute(SESSION_USER, userUuid);
    }

    @PostMapping("/auth/logout")
    public void logout(HttpSession session) {
        session.invalidate();
    }

    @GetMapping("/users/me")
    public UserProfileResponse getMyProfile(HttpSession session) {
        return UserProfileResponse.from(userService.getProfile(currentUser(session)));
    }

    @PutMapping("/users/me")
    public void updateProfile(@Valid @RequestBody UpdateProfileRequest req, HttpSession session) {
        userService.updateProfile(currentUser(session), req.userName(), req.phone(), req.birthDate());
    }

    @PutMapping("/users/me/password")
    public void changePassword(@Valid @RequestBody ChangePasswordRequest req, HttpSession session) {
        userService.changePassword(currentUser(session), req.currentPassword(), req.newPassword());
    }

    @DeleteMapping("/users/me")
    public void withdraw(HttpSession session) {
        userService.withdraw(currentUser(session));
        session.invalidate();
    }

    /** The logged-in user's uuid, or 401 (via {@link NotLoggedInException}) if there is none. */
    private UUID currentUser(HttpSession session) {
        UUID userUuid = (UUID) session.getAttribute(SESSION_USER);
        if (userUuid == null) {
            throw new NotLoggedInException();
        }
        return userUuid;
    }
}
