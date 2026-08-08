-- Preserve the canonical marketplace source on an immutable quotation hotel line.
-- These are logical identifiers, not foreign keys: catalog rows may later be unpublished while an
-- issued quotation must retain enough identity to raise the corresponding marketplace booking.
ALTER TABLE IF EXISTS quotation_hotels
    ADD COLUMN IF NOT EXISTS platform_hotel_public_id uuid,
    ADD COLUMN IF NOT EXISTS platform_room_public_id uuid,
    ADD COLUMN IF NOT EXISTS platform_meal_plan_public_id uuid;
