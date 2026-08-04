package com.example.bookserver.purchase;

import java.util.UUID;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * Persistence for an order's snapshotted delivery address. Written once when the order is
 * placed and read back with the order; never updated by state transitions. Column names map
 * to camel-case properties via map-underscore-to-camel-case.
 */
@Mapper
public interface OrderAddressMapper {

    // created_at is omitted — the DB fills it via DEFAULT CURRENT_TIMESTAMP
    @Insert("""
            INSERT INTO order_address
                (purchase_uuid, recipient, phone, country, road_address, detail_address, postal_code)
            VALUES
                (#{purchaseUuid}, #{recipient}, #{phone}, #{country},
                 #{roadAddress}, #{detailAddress}, #{postalCode})
            """)
    void insert(OrderAddress orderAddress);

    @Select("SELECT * FROM order_address WHERE purchase_uuid = #{purchaseUuid}")
    OrderAddress findByPurchaseUuid(UUID purchaseUuid);
}
