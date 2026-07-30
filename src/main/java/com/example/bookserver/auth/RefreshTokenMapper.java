package com.example.bookserver.auth;

import java.util.UUID;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface RefreshTokenMapper {

    // used/revoked default FALSE and created_at defaults to CURRENT_TIMESTAMP in the DB
    @Insert("""
            INSERT INTO refresh_token (token_id, family_id, user_uuid, token_hash, expires_at)
            VALUES (#{tokenId}, #{familyId}, #{userUuid}, #{tokenHash}, #{expiresAt})
            """)
    void insert(RefreshToken token);

    // lookup on refresh: the presented opaque token is hashed and matched here
    @Select("SELECT * FROM refresh_token WHERE token_hash = #{tokenHash}")
    RefreshToken findByHash(String tokenHash);

    // rotation: mark a token consumed so a later replay is detectable
    @Update("UPDATE refresh_token SET used = TRUE WHERE token_id = #{tokenId}")
    void markUsed(UUID tokenId);

    // reuse detection / logout: kill every token in the family at once
    @Update("UPDATE refresh_token SET revoked = TRUE WHERE family_id = #{familyId}")
    void revokeFamily(UUID familyId);
}
