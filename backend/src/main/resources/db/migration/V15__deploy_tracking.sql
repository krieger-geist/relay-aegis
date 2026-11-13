-- The last image that passed several consecutive health checks: the image a bad Docker deploy is rolled back to.
ALTER TABLE monitored_applications ADD COLUMN last_good_image_id VARCHAR(100);
ALTER TABLE monitored_applications ADD COLUMN last_good_image_ref VARCHAR(200);
ALTER TABLE monitored_applications ADD COLUMN last_good_at TIMESTAMP WITH TIME ZONE;
