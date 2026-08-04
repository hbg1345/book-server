-- Shipment tracking number, captured when an order ships (…→SHIPPING). One per order, fully
-- dependent on purchase_uuid, so it lives on purchase_current rather than in a side table.
-- It is set by a dedicated UPDATE on the SHIPPING transition and is deliberately NOT part of
-- the state-change upsert's DO UPDATE SET, so later transitions (DELIVERED/CONFIRMED) never
-- overwrite it back to NULL.
ALTER TABLE purchase_current ADD COLUMN tracking_number VARCHAR(64);
