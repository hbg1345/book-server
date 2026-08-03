package com.example.bookserver.user;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.example.bookserver.common.Uuids;

@Service
public class UserService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserMapper userMapper, PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Register a new user. The raw password is hashed (never stored in plaintext).
     * Returns the generated user_uuid.
     *
     * @throws DuplicateUserIdException if the user_id is already taken
     */
    public UUID register(String userId, String rawPassword, String userName,
                         String phone, LocalDate birthDate) {
        if (isUserIdTaken(userId)) {
            throw new DuplicateUserIdException(userId);
        }
        UUID userUuid = Uuids.newId();
        User user = new User();
        user.setUserUuid(userUuid);
        user.setUserId(userId);
        user.setUserPassword(passwordEncoder.encode(rawPassword));   // hash, never plaintext
        user.setUserName(userName);
        user.setPhone(phone);
        user.setBirthDate(birthDate);
        user.setRole(Role.USER);   // self-registration is always a plain user; ADMIN is granted, never requested
        userMapper.insert(user);
        return userUuid;
    }

    public boolean isUserIdTaken(String userId) {
        return userMapper.findByUserId(userId) != null;
    }

    /** The user's role, used to stamp the access token with the right authority. */
    public Role getRole(UUID userUuid) {
        return requireUser(userUuid).getRole();
    }

    /**
     * Ensure an account with this login id exists and holds the ADMIN role, creating it
     * if absent. Idempotent — safe to call on every startup. There is no public path to
     * ADMIN (register always yields USER); this is the sole way an admin comes to exist.
     */
    public UUID ensureAdminAccount(String userId, String rawPassword, String userName,
                                   String phone, LocalDate birthDate) {
        User existing = userMapper.findByUserId(userId);
        UUID userUuid = (existing != null)
                ? existing.getUserUuid()
                : register(userId, rawPassword, userName, phone, birthDate);
        userMapper.updateRole(userUuid, Role.ADMIN);
        return userUuid;
    }

    /**
     * Authenticate a login id + raw password and return the user_uuid.
     * Uses one generic failure for both "no such id" and "wrong password" so the
     * response never reveals whether the id exists.
     *
     * @throws InvalidCredentialsException if the id is unknown or the password is wrong
     */
    public UUID login(String userId, String rawPassword) {
        User user = userMapper.findByUserId(userId);
        if (user == null || !passwordEncoder.matches(rawPassword, user.getUserPassword())) {
            throw new InvalidCredentialsException();
        }
        return user.getUserUuid();
    }

    /** The user's profile. Note: the returned User still carries the password hash;
     *  the controller must not serialize it. */
    public User getProfile(UUID userUuid) {
        return requireUser(userUuid);
    }

    /** Update the mutable profile fields. user_id and password are left unchanged. */
    public void updateProfile(UUID userUuid, String userName, String phone, LocalDate birthDate) {
        User user = requireUser(userUuid);
        user.setUserName(userName);
        user.setPhone(phone);
        user.setBirthDate(birthDate);
        userMapper.update(user);   // keeps the loaded (unchanged) password hash
    }

    /**
     * Change the password: verify the current one against the stored hash, then
     * store the hash of the new one.
     *
     * @throws InvalidPasswordException if the current password does not match
     */
    public void changePassword(UUID userUuid, String currentRawPassword, String newRawPassword) {
        User user = requireUser(userUuid);
        if (!passwordEncoder.matches(currentRawPassword, user.getUserPassword())) {
            throw new InvalidPasswordException();
        }
        user.setUserPassword(passwordEncoder.encode(newRawPassword));
        userMapper.update(user);
    }

    /** Delete the user (cart/purchase rows cascade via their FKs). */
    public void withdraw(UUID userUuid) {
        requireUser(userUuid);
        userMapper.delete(userUuid);
    }

    private User requireUser(UUID userUuid) {
        User user = userMapper.findById(userUuid);
        if (user == null) {
            throw new UserNotFoundException(userUuid);
        }
        return user;
    }
}
