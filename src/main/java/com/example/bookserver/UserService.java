package com.example.bookserver;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

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
        userMapper.insert(user);
        return userUuid;
    }

    public boolean isUserIdTaken(String userId) {
        return userMapper.findByUserId(userId) != null;
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
