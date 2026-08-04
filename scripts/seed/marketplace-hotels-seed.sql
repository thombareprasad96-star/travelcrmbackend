-- ===========================================================================
-- Hotel Marketplace — TEST DATA SEED
-- ---------------------------------------------------------------------------
-- Seeds the SuperAdmin-owned global catalog (platform_hotels + rooms + meal
-- plans + amenities + room images) so the marketplace has something to search,
-- open and import while the feature is being tested.
--
-- NOT a Flyway migration. It lives outside src/main/resources/db/migration on
-- purpose, so it is never picked up by spring.flyway.locations and never ships
-- inside the jar. Run it by hand:
--
--   psql -h localhost -U postgres -d travel_crm -f scripts/seed/marketplace-hotels-seed.sql
--
-- SAFE TO RE-RUN. Every statement is INSERT-only and guarded by NOT EXISTS on a
-- natural key (hotel name, room name, meal-plan code), so a second run inserts
-- nothing. It never UPDATEs and never DELETEs — editing a seeded hotel by hand
-- will not be reverted by re-running this.
--
-- Catalog rows carry NO tenant_id (PlatformHotel extends BaseEntity, not
-- BaseTenantEntity) — the platform owns them and every entitled tenant reads
-- the same rows. Nothing here is scoped to one tenant.
--
-- Geography is deliberately matched to the cities that already exist in the
-- dev tenant's own master, so "Import to my masters" resolves instead of
-- failing with LOCATION_MAPPING_REQUIRED (HotelGeoResolver matches on ISO
-- country code + city name, and never guesses).
--
-- Rows created: 7 hotels (6 ACTIVE = sellable, 1 DRAFT = publish-flow test),
--               19 rooms, 18 meal plans, ~40 amenities, 1 image per room.
-- ===========================================================================

BEGIN;

-- ── 1. Hotels ──────────────────────────────────────────────────────────────
-- public_id defaults to gen_random_uuid(); catalog_version starts at 1;
-- confirmation_mode is SUPERADMIN_APPROVAL because INSTANT is not implemented.
INSERT INTO platform_hotels (
    name, status, country_code, state_name, city_name, city_code, address,
    latitude, longitude, stars, rating, website, map_url, overview,
    primary_image_url, phone, email, confirmation_mode, catalog_version,
    created_by, updated_by, created_at, updated_at)
SELECT v.name, v.status, v.country_code, v.state_name, v.city_name, v.city_code,
       v.address, v.latitude, v.longitude, v.stars, v.rating, v.website,
       v.map_url, v.overview, v.primary_image_url, v.phone, v.email,
       'SUPERADMIN_APPROVAL', 1,
       'seed-script', 'seed-script', now(), now()
FROM (VALUES
    -- ── Goa (tenant city: Panaji, country IN) ──
    ('Coral Sands Beach Resort', 'ACTIVE', 'IN', 'Goa', 'Panaji', 'GOI',
     'Miramar Beach Road, Panaji, Goa 403001', 15.4780, 73.8080, 4, 4.3,
     'https://example.com/coral-sands', 'https://maps.google.com/?q=15.4780,73.8080',
     'A relaxed four-star beachfront resort a short walk from Miramar Beach. Two restaurants, a large outdoor pool and an in-house travel desk make it a dependable pick for family and honeymoon groups on a Goa circuit.',
     'https://images.unsplash.com/photo-1571003123894-1f0594d2b5d9?w=1200&q=80',
     '+91 832 245 1100', 'reservations@coralsands.example.com'),

    ('Miramar Bay Hotel & Spa', 'ACTIVE', 'IN', 'Goa', 'Panaji', 'GOI',
     'Dayanand Bandodkar Marg, Miramar, Panaji, Goa 403002', 15.4820, 73.8045, 5, 4.7,
     'https://example.com/miramar-bay', 'https://maps.google.com/?q=15.4820,73.8045',
     'Five-star bayfront property with a full-service spa, three dining venues and 24-hour in-room dining. Banquet space for 300 makes it the usual choice for destination weddings booked out of this catalog.',
     'https://images.unsplash.com/photo-1566073771259-6a8506099945?w=1200&q=80',
     '+91 832 246 7788', 'stay@miramarbay.example.com'),

    -- ── Dubai (tenant city: Dubai City, country AE) ──
    ('Marina Bay Grand', 'ACTIVE', 'AE', 'Dubai', 'Dubai City', 'DXB',
     'Al Marsa Street, Dubai Marina, Dubai', 25.0805, 55.1403, 5, 4.6,
     'https://example.com/marina-bay-grand', 'https://maps.google.com/?q=25.0805,55.1403',
     'Landmark tower on Dubai Marina with a rooftop infinity pool and direct walkway access to the Marina Mall. Complimentary shuttle to Jumeirah Beach; airport transfers on request.',
     'https://images.unsplash.com/photo-1582719508461-905c673771fd?w=1200&q=80',
     '+971 4 399 4000', 'bookings@marinabaygrand.example.com'),

    ('Desert Rose Boutique Hotel', 'ACTIVE', 'AE', 'Dubai', 'Dubai City', 'DXB',
     '14B Al Muraqqabat Road, Deira, Dubai', 25.2650, 55.3300, 3, 3.9,
     'https://example.com/desert-rose', 'https://maps.google.com/?q=25.2650,55.3300',
     'Compact value hotel in Deira, two minutes from the metro and walking distance to the Gold Souk. Suits budget groups and short stopovers rather than leisure stays.',
     'https://images.unsplash.com/photo-1445019980597-93fa8acb246c?w=1200&q=80',
     '+971 4 262 1188', 'front.desk@desertrose.example.com'),

    -- ── Thailand (tenant city: Phuket Town, country TH) ──
    ('Andaman Pearl Resort', 'ACTIVE', 'TH', 'Phuket', 'Phuket Town', 'HKT',
     '188 Thepkrasattri Road, Phuket Town, Phuket 83000', 7.8850, 98.3900, 4, 4.4,
     'https://example.com/andaman-pearl', 'https://maps.google.com/?q=7.8850,98.3900',
     'Garden resort built around a free-form pool, twenty minutes from Patong. Daily island-hopping desk, Thai cookery classes and a kids club — the standard Phuket package inclusion set.',
     'https://images.unsplash.com/photo-1520250497591-112f2f40a3f4?w=1200&q=80',
     '+66 76 211 900', 'reserve@andamanpearl.example.com'),

    -- ── Singapore (tenant city: Singapore City, country SG) ──
    ('Orchard Central Suites', 'ACTIVE', 'SG', 'Central Region', 'Singapore City', 'SIN',
     '181 Orchard Road, Singapore 238896', 1.3006, 103.8398, 5, 4.8,
     'https://example.com/orchard-central', 'https://maps.google.com/?q=1.3006,103.8398',
     'All-suite property on Orchard Road, directly above Somerset MRT. Every unit has a kitchenette, which is why it is the usual recommendation for longer family stays and multi-generation groups.',
     'https://images.unsplash.com/photo-1564501049412-61c2a3083791?w=1200&q=80',
     '+65 6735 1234', 'enquiries@orchardcentral.example.com'),

    -- ── DRAFT: not sellable. Kept unpublished on purpose so the SuperAdmin
    --    publish/unpublish flow (step-up MFA guarded) has something to act on,
    --    and so tenant search can be verified to EXCLUDE it. Udaipur is also
    --    absent from the dev tenant geography, so publishing it exercises the
    --    LOCATION_MAPPING_REQUIRED import failure with a real message.
    ('Lakeview Palace Udaipur', 'DRAFT', 'IN', 'Rajasthan', 'Udaipur', 'UDR',
     'Lake Pichola East Bank, Udaipur, Rajasthan 313001', 24.5760, 73.6800, 5, 4.9,
     'https://example.com/lakeview-palace', 'https://maps.google.com/?q=24.5760,73.6800',
     'Heritage palace conversion on the east bank of Lake Pichola. Courtyard dining, boat jetty and a rooftop bar overlooking the City Palace.',
     'https://images.unsplash.com/photo-1542314831-068cd1dbfeeb?w=1200&q=80',
     '+91 294 242 8888', 'reservations@lakeviewpalace.example.com')
) AS v(name, status, country_code, state_name, city_name, city_code, address,
       latitude, longitude, stars, rating, website, map_url, overview,
       primary_image_url, phone, email)
WHERE NOT EXISTS (
    SELECT 1 FROM platform_hotels p
    WHERE p.name = v.name AND p.deleted_at IS NULL);


-- ── 2. Amenities ───────────────────────────────────────────────────────────
-- Mirrors the tenant Hotel.amenities @ElementCollection shape, so the sync is
-- a straight copy. No PK on this table — guard on the (hotel, amenity) pair.
INSERT INTO platform_hotel_amenities (platform_hotel_id, amenity)
SELECT h.id, a.amenity
FROM (VALUES
    ('Coral Sands Beach Resort', 'Free Wi-Fi'),
    ('Coral Sands Beach Resort', 'Outdoor Pool'),
    ('Coral Sands Beach Resort', 'Beachfront'),
    ('Coral Sands Beach Resort', 'Multi-cuisine Restaurant'),
    ('Coral Sands Beach Resort', 'Airport Transfer'),
    ('Coral Sands Beach Resort', 'Travel Desk'),

    ('Miramar Bay Hotel & Spa', 'Free Wi-Fi'),
    ('Miramar Bay Hotel & Spa', 'Spa and Wellness Centre'),
    ('Miramar Bay Hotel & Spa', 'Infinity Pool'),
    ('Miramar Bay Hotel & Spa', 'Banquet Hall'),
    ('Miramar Bay Hotel & Spa', 'Fitness Centre'),
    ('Miramar Bay Hotel & Spa', '24-hour Room Service'),
    ('Miramar Bay Hotel & Spa', 'Valet Parking'),

    ('Marina Bay Grand', 'Free Wi-Fi'),
    ('Marina Bay Grand', 'Rooftop Infinity Pool'),
    ('Marina Bay Grand', 'Fitness Centre'),
    ('Marina Bay Grand', 'Business Centre'),
    ('Marina Bay Grand', 'Beach Shuttle'),
    ('Marina Bay Grand', 'Concierge'),

    ('Desert Rose Boutique Hotel', 'Free Wi-Fi'),
    ('Desert Rose Boutique Hotel', 'Airport Transfer'),
    ('Desert Rose Boutique Hotel', 'Cafe'),
    ('Desert Rose Boutique Hotel', 'Laundry Service'),

    ('Andaman Pearl Resort', 'Free Wi-Fi'),
    ('Andaman Pearl Resort', 'Outdoor Pool'),
    ('Andaman Pearl Resort', 'Kids Club'),
    ('Andaman Pearl Resort', 'Spa'),
    ('Andaman Pearl Resort', 'Tour Desk'),
    ('Andaman Pearl Resort', 'Poolside Bar'),

    ('Orchard Central Suites', 'Free Wi-Fi'),
    ('Orchard Central Suites', 'Kitchenette'),
    ('Orchard Central Suites', 'Rooftop Pool'),
    ('Orchard Central Suites', 'Fitness Centre'),
    ('Orchard Central Suites', 'MRT Access'),
    ('Orchard Central Suites', 'Self-service Laundry'),

    ('Lakeview Palace Udaipur', 'Free Wi-Fi'),
    ('Lakeview Palace Udaipur', 'Lake View'),
    ('Lakeview Palace Udaipur', 'Heritage Property'),
    ('Lakeview Palace Udaipur', 'Rooftop Restaurant'),
    ('Lakeview Palace Udaipur', 'Boat Jetty')
) AS a(hotel_name, amenity)
JOIN platform_hotels h ON h.name = a.hotel_name AND h.deleted_at IS NULL
WHERE NOT EXISTS (
    SELECT 1 FROM platform_hotel_amenities x
    WHERE x.platform_hotel_id = h.id AND x.amenity = a.amenity);


-- ── 3. Room categories ─────────────────────────────────────────────────────
-- NO price column, by design: a room rate depends on date, meal plan and
-- occupancy and belongs to a rate calendar. Occupancy IS stored, because it is
-- validated at booking time (a room that sleeps 2 must reject 4 guests).
INSERT INTO platform_hotel_rooms (
    platform_hotel_id, name, max_adults, max_children, max_occupancy,
    bed_type, size, description, active,
    created_by, updated_by, created_at, updated_at)
SELECT h.id, r.name, r.max_adults, r.max_children, r.max_occupancy,
       r.bed_type, r.size, r.description, true,
       'seed-script', 'seed-script', now(), now()
FROM (VALUES
    ('Coral Sands Beach Resort', 'Deluxe Garden View', 2, 1, 3, 'King', '32 sqm',
     'Ground and first-floor rooms opening onto the garden. King bed, work desk, rain shower.'),
    ('Coral Sands Beach Resort', 'Premium Sea View', 2, 2, 4, 'King', '40 sqm',
     'Upper-floor room with a private balcony facing the Arabian Sea. Sofa bed accommodates two children.'),
    ('Coral Sands Beach Resort', 'Family Suite', 4, 2, 6, 'Two Queen Beds', '62 sqm',
     'Two-room suite with a connecting door, two bathrooms and a dining nook. Fits two families sharing.'),

    ('Miramar Bay Hotel & Spa', 'Superior Room', 2, 1, 3, 'King or Twin', '38 sqm',
     'Bay-facing room with a marble bathroom, walk-in shower and a Nespresso machine.'),
    ('Miramar Bay Hotel & Spa', 'Executive Suite', 2, 2, 4, 'King', '65 sqm',
     'Separate living room, executive lounge access and complimentary evening cocktails.'),
    ('Miramar Bay Hotel & Spa', 'Presidential Villa', 4, 2, 6, 'Two King Beds', '150 sqm',
     'Standalone villa with a plunge pool, butler service and a private dining terrace.'),

    ('Marina Bay Grand', 'Classic King', 2, 1, 3, 'King', '36 sqm',
     'Floor-to-ceiling windows over the marina, smart TV, Bluetooth audio.'),
    ('Marina Bay Grand', 'Skyline Suite', 2, 2, 4, 'King', '72 sqm',
     'Corner suite on floors 30 and above with a wraparound view and a soaking tub.'),
    ('Marina Bay Grand', 'Two-Bedroom Residence', 4, 2, 6, 'King + Two Twin', '120 sqm',
     'Full kitchen, washer-dryer and two en-suite bedrooms. Priced per residence, not per room.'),

    ('Desert Rose Boutique Hotel', 'Standard Twin', 2, 0, 2, 'Two Twin Beds', '22 sqm',
     'Compact twin room with a shower cubicle, mini fridge and blackout curtains.'),
    ('Desert Rose Boutique Hotel', 'Deluxe Double', 2, 1, 3, 'Double', '28 sqm',
     'Slightly larger room with a seating chair and a city-facing window.'),

    ('Andaman Pearl Resort', 'Garden Bungalow', 2, 1, 3, 'King', '35 sqm',
     'Freestanding bungalow with an outdoor shower and a private sit-out.'),
    ('Andaman Pearl Resort', 'Pool Access Deluxe', 2, 2, 4, 'King', '45 sqm',
     'Terrace steps directly into the lagoon pool. Popular with honeymoon bookings.'),
    ('Andaman Pearl Resort', 'Ocean Breeze Suite', 3, 2, 5, 'King + Sofa Bed', '80 sqm',
     'Top-floor suite with a wide sea-facing balcony and a separate lounge.'),

    ('Orchard Central Suites', 'City Studio', 2, 0, 2, 'Queen', '30 sqm',
     'Studio with a kitchenette, induction hob and a compact dining table for two.'),
    ('Orchard Central Suites', 'Club Room', 2, 1, 3, 'King', '42 sqm',
     'Club lounge access with all-day refreshments and complimentary pressing.'),
    ('Orchard Central Suites', 'Panorama Suite', 4, 2, 6, 'King + Two Single', '85 sqm',
     'Two-bedroom suite with a full kitchen and a corner window over Orchard Road.'),

    ('Lakeview Palace Udaipur', 'Heritage Room', 2, 1, 3, 'King', '40 sqm',
     'Restored palace room with hand-painted frescoes and a courtyard view.'),
    ('Lakeview Palace Udaipur', 'Lake View Suite', 2, 2, 4, 'King', '75 sqm',
     'Arched windows onto Lake Pichola, a private sit-out and a claw-foot tub.')
) AS r(hotel_name, name, max_adults, max_children, max_occupancy, bed_type, size, description)
JOIN platform_hotels h ON h.name = r.hotel_name AND h.deleted_at IS NULL
WHERE NOT EXISTS (
    SELECT 1 FROM platform_hotel_rooms x
    WHERE x.platform_hotel_id = h.id AND x.name = r.name AND x.deleted_at IS NULL);


-- ── 4. Room images ─────────────────────────────────────────────────────────
-- One image per seeded room, rotated over a small set so the gallery is not
-- uniform. Cosmetic only; the FE reads these as plain URLs.
INSERT INTO platform_hotel_room_images (platform_room_id, image_url)
SELECT r.id,
       (ARRAY[
         'https://images.unsplash.com/photo-1611892440504-42a792e24d32?w=1000&q=80',
         'https://images.unsplash.com/photo-1618773928121-c32242e63f39?w=1000&q=80',
         'https://images.unsplash.com/photo-1590490360182-c33d57733427?w=1000&q=80',
         'https://images.unsplash.com/photo-1631049307264-da0ec9d70304?w=1000&q=80'
       ])[(r.id % 4) + 1]
FROM platform_hotel_rooms r
JOIN platform_hotels h ON h.id = r.platform_hotel_id AND h.deleted_at IS NULL
WHERE r.deleted_at IS NULL
  AND h.created_by = 'seed-script'
  AND NOT EXISTS (
      SELECT 1 FROM platform_hotel_room_images i WHERE i.platform_room_id = r.id);


-- ── 5. Meal plans ──────────────────────────────────────────────────────────
-- `code` is the stable machine value the sync matches on; `name` is free text a
-- human reads. NO price column — a meal plan is an inclusion, not a rate.
INSERT INTO platform_hotel_meal_plans (
    platform_hotel_id, code, name, description, active,
    created_by, updated_by, created_at, updated_at)
SELECT h.id, m.code, m.name, m.description, true,
       'seed-script', 'seed-script', now(), now()
FROM (VALUES
    ('Coral Sands Beach Resort', 'EP',  'Room Only',              'Accommodation only. No meals included.'),
    ('Coral Sands Beach Resort', 'CP',  'Breakfast Included',     'Daily buffet breakfast at the all-day dining restaurant.'),
    ('Coral Sands Beach Resort', 'MAP', 'Breakfast + Dinner',     'Buffet breakfast and set-menu dinner. Beverages charged separately.'),

    ('Miramar Bay Hotel & Spa', 'CP',  'Breakfast Included',      'Buffet breakfast for all occupants of the room.'),
    ('Miramar Bay Hotel & Spa', 'MAP', 'Half Board',              'Breakfast plus lunch or dinner, guest choice at check-in.'),
    ('Miramar Bay Hotel & Spa', 'AP',  'Full Board',              'Breakfast, lunch and dinner. Excludes alcoholic beverages.'),

    ('Marina Bay Grand', 'EP',  'Room Only',                      'Accommodation only.'),
    ('Marina Bay Grand', 'CP',  'Bed and Breakfast',              'International breakfast buffet, served until 11:00.'),
    ('Marina Bay Grand', 'MAP', 'Breakfast + One Meal',           'Breakfast plus a set lunch or dinner at any hotel restaurant.'),

    ('Desert Rose Boutique Hotel', 'EP', 'Room Only',             'Accommodation only.'),
    ('Desert Rose Boutique Hotel', 'CP', 'Continental Breakfast', 'Continental breakfast at the ground-floor cafe.'),

    ('Andaman Pearl Resort', 'CP',  'Breakfast Included',         'Thai and continental breakfast buffet.'),
    ('Andaman Pearl Resort', 'MAP', 'Half Board',                 'Breakfast plus dinner at the poolside grill.'),
    ('Andaman Pearl Resort', 'AP',  'Full Board',                 'All three meals, including the Thursday seafood barbecue.'),

    ('Orchard Central Suites', 'EP', 'Room Only',                 'Accommodation only. Kitchenette provided in every suite.'),
    ('Orchard Central Suites', 'CP', 'Breakfast Included',        'Breakfast at the level 4 dining room for up to two guests.'),

    ('Lakeview Palace Udaipur', 'CP',  'Breakfast Included',      'Breakfast served in the palace courtyard.'),
    ('Lakeview Palace Udaipur', 'MAP', 'Breakfast + Dinner',      'Breakfast plus a Rajasthani thali dinner on the rooftop.')
) AS m(hotel_name, code, name, description)
JOIN platform_hotels h ON h.name = m.hotel_name AND h.deleted_at IS NULL
WHERE NOT EXISTS (
    SELECT 1 FROM platform_hotel_meal_plans x
    WHERE x.platform_hotel_id = h.id AND x.code = m.code AND x.deleted_at IS NULL);

COMMIT;

-- ── Verification ───────────────────────────────────────────────────────────
SELECT h.id,
       h.name,
       h.status,
       h.city_name,
       h.country_code,
       h.stars,
       (SELECT count(*) FROM platform_hotel_rooms      r WHERE r.platform_hotel_id = h.id) AS rooms,
       (SELECT count(*) FROM platform_hotel_meal_plans m WHERE m.platform_hotel_id = h.id) AS meal_plans,
       (SELECT count(*) FROM platform_hotel_amenities  a WHERE a.platform_hotel_id = h.id) AS amenities
FROM platform_hotels h
WHERE h.deleted_at IS NULL
ORDER BY h.status, h.name;
