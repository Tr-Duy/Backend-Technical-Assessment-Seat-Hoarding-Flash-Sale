INSERT INTO inventory (sku_id, stock) VALUES ('PHONE-X', 100)
ON DUPLICATE KEY UPDATE stock = 100;
