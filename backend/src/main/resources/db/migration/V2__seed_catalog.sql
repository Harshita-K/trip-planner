-- Demo catalog. Event dates are relative to migration time so the seed is always "upcoming".
-- Coordinates are approximate.

insert into events (id, title, category, venue, lat, lng, city, start_time, price, capacity, available, description) values
  (gen_random_uuid(), 'Indie Nights Live',            'music',    'Phoenix Marketcity Arena',   12.9975, 77.6960, 'Bengaluru', date_trunc('day', now()) + interval '5 days 19 hours',  1499.00, 500, 500, 'Four indie bands, one long night.'),
  (gen_random_uuid(), 'Bengaluru Comedy Carnival',    'comedy',   'Chowdiah Memorial Hall',     13.0006, 77.5776, 'Bengaluru', date_trunc('day', now()) + interval '7 days 20 hours',   799.00, 300, 300, 'Stand-up showcase with headliners.'),
  (gen_random_uuid(), 'Heritage Walk: Old Bangalore', 'history',  'KR Market Gate',             12.9644, 77.5770, 'Bengaluru', date_trunc('day', now()) + interval '3 days 7 hours',    499.00,  25,  25, 'Guided walk through the pete area.'),
  (gen_random_uuid(), 'Craft Beer Festival',          'food',     'Jayamahal Palace Grounds',   13.0012, 77.5968, 'Bengaluru', date_trunc('day', now()) + interval '10 days 16 hours', 999.00, 800, 800, 'Thirty breweries, live music.'),
  (gen_random_uuid(), 'Lalbagh Flower Show',          'nature',   'Lalbagh Glass House',        12.9507, 77.5848, 'Bengaluru', date_trunc('day', now()) + interval '14 days 9 hours',   100.00, 2000, 2000, 'Annual flower show.'),
  (gen_random_uuid(), 'Tech Startup Mixer',           'business', 'UB City Rooftop',            12.9716, 77.5963, 'Bengaluru', date_trunc('day', now()) + interval '2 days 18 hours',   299.00, 120, 120, 'Founders, VCs and builders.'),
  (gen_random_uuid(), 'Carnatic Evening',             'music',    'Bangalore Gayana Samaja',    12.9561, 77.5740, 'Bengaluru', date_trunc('day', now()) + interval '6 days 18 hours',   350.00, 200, 200, 'Classical vocal concert.'),
  (gen_random_uuid(), 'Jaipur Literature Preview',    'books',    'Diggi Palace',               26.9055, 75.8110, 'Jaipur',    date_trunc('day', now()) + interval '20 days 10 hours',  0.00,  400, 400, 'Preview sessions and signings.'),
  (gen_random_uuid(), 'Goa Sunset Music Fest',        'music',    'Vagator Beach',              15.6020, 73.7340, 'Goa',       date_trunc('day', now()) + interval '25 days 16 hours', 2999.00, 1500, 1500, 'Electronic music by the sea.');

insert into trips (id, destination, lat, lng, base_price, duration_days, description) values
  (gen_random_uuid(), 'Bengaluru City Explorer', 12.9716, 77.5946, 8999.00, 3, 'Gardens, palaces and the best dosa in town.'),
  (gen_random_uuid(), 'Mysuru Royal Weekend',    12.2958, 76.6394, 6499.00, 2, 'Mysore Palace, Chamundi Hills and silk.'),
  (gen_random_uuid(), 'Coorg Coffee Trail',      12.4244, 75.7382, 11999.00, 3, 'Plantation stays and waterfalls.'),
  (gen_random_uuid(), 'Jaipur Pink City',        26.9124, 75.7873, 14999.00, 4, 'Forts, bazaars and Rajasthani thalis.');
