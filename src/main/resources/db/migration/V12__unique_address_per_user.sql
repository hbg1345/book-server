-- One physical address, once per user's book. A create had nothing for two submissions to
-- meet on (address_uuid is minted per call), so a double-clicked form left two rows; this
-- index is the collision point. See #61.
--
-- Columns chosen: recipient and phone are IN, because the same street address sent to two
-- different people is two legitimate entries (a gift to a parent, an order to a colleague).
-- `alias` is OUT, because it is only the user's label — the same address saved as both
-- "Home" and "집" is a duplicate, not two addresses.
--
-- detail_address is wrapped in COALESCE: it is nullable, and a unique index treats two NULLs
-- as distinct, so addresses without a unit number would have slipped past the constraint
-- entirely — exactly the rows this is meant to catch.

-- Any duplicates already saved would make the index creation fail, so collapse them first.
-- The keeper is the default one if there is one, oldest otherwise. Safe to delete the rest:
-- order_address.source_address_uuid is deliberately not a foreign key (V6), so past orders
-- keep their own snapshot of the address and are untouched by this.
DELETE FROM address a
USING (
    SELECT address_uuid,
           ROW_NUMBER() OVER (
               PARTITION BY user_uuid, country, postal_code, road_address,
                            COALESCE(detail_address, ''), recipient, phone
               ORDER BY is_default DESC, created_at
           ) AS row_in_group
    FROM address
) dupe
WHERE a.address_uuid = dupe.address_uuid
  AND dupe.row_in_group > 1;

CREATE UNIQUE INDEX uq_address_no_duplicate_per_user
    ON address (user_uuid, country, postal_code, road_address,
                COALESCE(detail_address, ''), recipient, phone);
